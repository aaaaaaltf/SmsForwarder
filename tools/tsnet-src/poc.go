// Package tsnetpoc 是「手机被控端不依赖 VpnService」的修复实现：
// 用 tsnet 在进程内以用户态建立 Tailscale 节点（netstack），
// 再通过 Go 侧 Dial / Listen / Status 提供直连能力。
//
// 该包只用于 gomobile bind 生成 AAR，供 Android/Kotlin 侧调用。
// 与 libtailscale 的区别：
//   - libtailscale 走 ipnlocal + VpnService（需要 Android VpnService 授权、需要 tun）；
//   - tsnet 走 netstack（纯用户态，不申请 VPN、不建 tun、不接管系统路由）。
package tsnetpoc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"sync"
	"time"
	_ "unsafe"

	"github.com/tailscale/tailscale-android/libtailscale/ifaceparse"
	"tailscale.com/feature/taildrop"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// InterfaceProvider 由 Android/Kotlin 侧实现，返回与 libtailscale AppContext
// 同格式的网络接口 JSON（由 java.net.NetworkInterface 枚举而来）。
//
// 为什么必须有它：Android 10+ 的 SELinux 禁止 App 直接读内核 netlink，
// Go 标准库的 net.Interfaces() 会返回 "route ip+net: netlinkrib: permission denied"，
// tsnet 在 netmon 初始化阶段就会因此启动失败（真机已复现）。
// libtailscale 能正常工作，是因为它在启动前注册了
// netmon.RegisterInterfaceGetter(走 Java 侧的接口列表)。这里复用同一套机制。
type InterfaceProvider interface {
	// GetInterfacesJSON 返回形如 [{"name":"wlan0","index":..,"mtu":..,"addrs":[...]}]
	GetInterfacesJSON() (string, error)
}

var (
	providerMu   sync.Mutex
	ifaceProvider InterfaceProvider
)

// init 在包被加载时注册接口获取器（默认返回空列表，避免退回 net.Interfaces 触发崩溃）。
func init() {
	// ★ 2026-11-05【被控端去 VPN】Android 上 Go 以 c-shared 库形态加载（gomobile bind），
	//   运行时被判定为 islibrary → 任何 panic 都会直接 abort（tombstone 只有 runtime.raise.abi0），
	//   且真机上 GOTRACEBACK 被外部置为 none，goroutine 栈完全不打印。
	//   这里在启动时强制打开完整栈输出，便于定位启动期 panic。
	debug.SetTraceback("all")

	// 必须在任何 LocalBackend.Start 之前完成（Start 会立刻调用 taildrop Extension.Init）。
	installTaildropFileOps()

	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		providerMu.Lock()
		p := ifaceProvider
		providerMu.Unlock()
		if p == nil {
			return nil, nil
		}
		js, err := p.GetInterfacesJSON()
		if err != nil {
			return nil, err
		}
		js = strings.TrimSpace(js)
		if js == "" || js == "[]" {
			return nil, nil
		}
		ifaces, _, err := ifaceparse.ParseInterfacesJSONAsNetmon([]byte(js))
		return ifaces, err
	})
}

// SetInterfaceProvider 注入 Android 侧的接口列表提供者（必须在 Start/Up 之前调用）。
func SetInterfaceProvider(p InterfaceProvider) {
	providerMu.Lock()
	ifaceProvider = p
	providerMu.Unlock()
}

// logPanic 在 panic 时把完整 Go 栈打印到 Android logcat（tag: GoLog/[tsnetpoc]）。
//
// ★ 2026-11-05【被控端去 VPN】为什么需要它：
//   Android 上 Go 运行时 panic 只会打出 "panic: ..." 与 "[signal SIGSEGV ...]" 两行，
//   随后的 goroutine 栈被 MIUI 的日志洪流截断/丢失，tombstone 里也只有 runtime.raise.abi0，
//   无法定位 nil pointer 的具体帧。用 recover + debug.Stack() 主动打印栈，再重新 panic
//   保持原有崩溃语义，即可在 logcat 中拿到完整调用链。
func logPanic(name string) {
	if r := recover(); r != nil {
		log.Printf("[tsnetpoc] !!PANIC(%s): %v\n%s", name, r, debug.Stack())
		panic(r)
	}
}

// ensureEnvDir 在环境变量未设置时注入一个可写的私有目录（先建目录再 setenv）。
func ensureEnvDir(key, dir string) {
	if _, ok := os.LookupEnv(key); ok {
		return
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		log.Printf("[tsnetpoc] 创建目录失败 %s=%s: %v", key, dir, err)
		return
	}
	_ = os.Setenv(key, dir)
}

// Node 是一个用户态 Tailscale 节点。
type Node struct {
	srv *tsnet.Server

	mu        sync.Mutex
	closers   []io.Closer
	listeners map[int]net.Listener
}

// ---------- ★ 2026-11-05【被控端去 VPN】Taildrop FileOps 兜底 ----------
//
// 真机崩溃根因（logcat 已抓到完整栈）：
//
//	panic: runtime error: invalid memory address or nil pointer dereference addr=0x0
//	  tailscale.com/feature/taildrop.(*Extension).onChangeProfile(ext.go:171)
//	  tailscale.com/ipnlocal.(*LocalBackend).setProfilePrefs
//	  ...  ↑ 登录成功、收到 netmap 后 26ms 触发
//
// 原因：tailscale 的 taildrop 包在 GOOS=android 下**不编译 fileops_fs.go**（该文件带
// `//go:build !android`），于是包级变量 `newFileOps` 恒为 nil，等着宿主 App 调用
// `Extension.SetFileOps(...)`（libtailscale 的 VPN 模式就是这么做的）。
// tsnet 从不调用 SetFileOps，所以 profile 变化时 `newFileOps(fileRoot)` 变成 nil 函数调用，
// 直接 SIGSEGV。
//
// 修复：taildrop 的 Android 端 FileOps 由“宿主”提供，libtailscale(VPN 模式)在
// LocalBackend 建好后调 Extension.SetFileOps；但 tsnet 没有暴露扩展实例，
// 且崩溃发生在 Extension.Init（由 LocalBackend.Start 内部触发），那时任何外部注入都来不及。
// 因此这里在**包初始化阶段**直接给 taildrop 的包级工厂变量 `newFileOps` 赋值
// （用 go:linkname 取到未导出变量，已用 android/arm64 目标实测可链接）。

//go:linkname taildropNewFileOps tailscale.com/feature/taildrop.newFileOps

var taildropNewFileOps func(dir string) (taildrop.FileOps, error)

// installTaildropFileOps 在包初始化时安装 Android 侧的 FileOps 兜底实现。
func installTaildropFileOps() {
	if taildropNewFileOps == nil {
		log.Printf("[tsnetpoc] 检测到 taildrop.newFileOps 为空，安装 App 私有目录实现")
		taildropNewFileOps = func(dir string) (taildrop.FileOps, error) {
			if err := os.MkdirAll(dir, 0o700); err != nil {
				return nil, err
			}
			log.Printf("[tsnetpoc] taildrop FileOps 使用目录: %s", dir)
			return dirFileOps{root: dir}, nil
		}
		return
	}
	log.Printf("[tsnetpoc] taildrop.newFileOps 已存在，无需兜底")
}

// dirFileOps 是基于 App 私有目录（os 文件系统）的 FileOps 实现。
type dirFileOps struct{ root string }

func (f dirFileOps) join(base string) (string, error) {
	if base == "" || base == "." || base == ".." ||
		filepath.IsAbs(base) || base != filepath.Base(base) ||
		strings.ContainsRune(base, os.PathSeparator) {
		return "", fmt.Errorf("taildrop: 非法文件名 %q", base)
	}
	return filepath.Join(f.root, base), nil
}

func (f dirFileOps) OpenWriter(name string, offset int64, perm os.FileMode) (io.WriteCloser, string, error) {
	p, err := f.join(name)
	if err != nil {
		return nil, "", err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return nil, "", err
	}
	file, err := os.OpenFile(p, os.O_CREATE|os.O_RDWR, perm)
	if err != nil {
		return nil, "", err
	}
	if offset != 0 {
		cur, err := file.Seek(0, io.SeekEnd)
		if err != nil {
			_ = file.Close()
			return nil, "", err
		}
		if offset < 0 || offset > cur {
			_ = file.Close()
			return nil, "", fmt.Errorf("taildrop: 写入偏移 %d 超出文件长度 %d", offset, cur)
		}
		if _, err := file.Seek(offset, io.SeekStart); err != nil {
			_ = file.Close()
			return nil, "", err
		}
		if err := file.Truncate(offset); err != nil {
			_ = file.Close()
			return nil, "", err
		}
	}
	return file, p, nil
}

func (f dirFileOps) Remove(name string) error {
	p, err := f.join(name)
	if err != nil {
		return err
	}
	return os.Remove(p)
}

func (f dirFileOps) Rename(oldPath, newName string) (string, error) {
	if _, err := f.join(newName); err != nil {
		return "", err
	}
	dst := filepath.Join(f.root, newName)
	// 同名文件已存在时追加 -1/-2… 后缀，避免覆盖已有文件。
	for i := 1; ; i++ {
		if _, err := os.Stat(dst); os.IsNotExist(err) {
			break
		} else if err != nil {
			return "", err
		}
		dst = filepath.Join(f.root, fmt.Sprintf("%s-%d", newName, i))
	}
	if err := os.Rename(oldPath, dst); err != nil {
		return "", err
	}
	return dst, nil
}

func (f dirFileOps) ListFiles() ([]string, error) {
	entries, err := os.ReadDir(f.root)
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if e.Type().IsRegular() {
			names = append(names, e.Name())
		}
	}
	return names, nil
}

func (f dirFileOps) Stat(name string) (fs.FileInfo, error) {
	p, err := f.join(name)
	if err != nil {
		return nil, err
	}
	return os.Stat(p)
}

func (f dirFileOps) OpenReader(name string) (io.ReadCloser, error) {
	p, err := f.join(name)
	if err != nil {
		return nil, err
	}
	return os.Open(p)
}

// Start 创建（但不启动）用户态节点。
//
//	stateDir    状态目录（App 私有目录，例如 files/tsnet/controlled）
//	hostname    节点名（建议与 App 现有 hostname 一致）
//	authKey     认证 key（可复用 App 现有的 authkey / OAuth 签发的 key）
//	controlURL  控制面地址，留空走官方默认
//	ephemeral   true 表示临时节点，退出即从 tailnet 移除
func Start(stateDir, hostname, authKey, controlURL string, ephemeral bool) (*Node, error) {
	defer logPanic("Start")
	if stateDir == "" {
		return nil, errors.New("tsnetpoc: stateDir 不能为空")
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return nil, fmt.Errorf("tsnetpoc: 创建状态目录失败: %w", err)
	}
	// ★ 2026-11-05【被控端去 VPN】Android 上没有可写的 HOME/TMPDIR：
	//   tailscale 的 logpolicy 依次尝试 state 文件目录 → os.UserCacheDir → 当前目录 → os.MkdirTemp，
	//   全部失败就 panic("no safe place found to store log state")（真机已复现）。
	//   libtailscale 的做法是在启动前注入 XDG_* 和 HOME，这里保持一致，并额外补 TMPDIR。
	ensureEnvDir("XDG_CACHE_HOME", filepath.Join(stateDir, "cache"))
	ensureEnvDir("XDG_CONFIG_HOME", filepath.Join(stateDir, "config"))
	ensureEnvDir("HOME", stateDir)
	ensureEnvDir("TMPDIR", filepath.Join(stateDir, "tmp"))
	s := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: controlURL,
		Ephemeral:  ephemeral,
		Logf: func(format string, args ...any) {
			log.Printf("[tsnetpoc] "+format, args...)
		},
	}
	return &Node{srv: s, listeners: make(map[int]net.Listener)}, nil
}

// Up 启动并登录，阻塞到在线或超时。
// 返回 JSON：{"self_ip":"100.x.x.x","backend_state":"Running","online":true}
func (n *Node) Up(timeoutSec int) (string, error) {
	defer logPanic("Up")
	if n == nil || n.srv == nil {
		return "", errors.New("tsnetpoc: 节点未初始化")
	}
	if timeoutSec <= 0 {
		timeoutSec = 30
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSec)*time.Second)
	defer cancel()

	st, err := n.srv.Up(ctx)
	if err != nil {
		return "", fmt.Errorf("tsnetpoc: Up 失败: %w", err)
	}
	selfIP := ""
	if st.Self != nil && len(st.Self.TailscaleIPs) > 0 {
		selfIP = st.Self.TailscaleIPs[0].String()
	}
	out := map[string]any{
		"self_ip":       selfIP,
		"backend_state": st.BackendState,
		"online":        st.BackendState == "Running",
	}
	b, _ := json.Marshal(out)
	return string(b), nil
}

// Status 返回节点状态（self_ip、backend_state）与尾网成员 IP 列表（IPv4，在线优先）。
// JSON：{"self_ip":"...","backend_state":"Running","online":true,
//        "peers":["100.x.x.x", ...]}
func (n *Node) Status(timeoutSec int) (string, error) {
	defer logPanic("Status")
	if n == nil || n.srv == nil {
		return "", errors.New("tsnetpoc: 节点未初始化")
	}
	if timeoutSec <= 0 {
		timeoutSec = 15
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSec)*time.Second)
	defer cancel()

	lc, err := n.srv.LocalClient()
	if err != nil {
		return "", fmt.Errorf("tsnetpoc: 取 LocalClient 失败: %w", err)
	}
	st, err := lc.Status(ctx)
	if err != nil {
		return "", fmt.Errorf("tsnetpoc: Status 失败: %w", err)
	}
	selfIP := ""
	if st.Self != nil && len(st.Self.TailscaleIPs) > 0 {
		selfIP = st.Self.TailscaleIPs[0].String()
	}
	// 在线成员优先，离线成员（LastSeen 新→旧）追加在后，可达性交给 Kotlin 侧实际探测。
	peers := make([]string, 0, len(st.Peer))
	for _, p := range st.Peer {
		if p == nil || !p.Online {
			continue
		}
		if ip := firstIPv4(p.TailscaleIPs); ip != "" && ip != selfIP {
			peers = append(peers, ip)
		}
	}
	// 注意：这里**刻意只返回在线成员**，与原 libtailscale 实现（getOnlineMemberIps 过滤 online）
	// 保持一致。离线的旧节点（例如改造前用 VPN 注册的那台）如果也进候选，
	// 每轮探测都会在它身上等一次超时（10s/个），会拖慢真正控制端的发现速度。
	out := map[string]any{
		"self_ip":       selfIP,
		"backend_state": st.BackendState,
		"online":        st.BackendState == "Running",
		"peers":         peers,
	}
	b, _ := json.Marshal(out)
	return string(b), nil
}

// firstIPv4 取第一个 IPv4 形式的 Tailscale IP。
func firstIPv4(ips []netip.Addr) string {
	for _, ip := range ips {
		if ip.Is4() {
			return ip.String()
		}
	}
	return ""
}

// ProbeTCP 通过用户态隧道拨号到 addr（例如 100.x.x.x:56789），
// 可选写入 payload，并尝试读取一次响应（最多等 2 秒）。
// 返回连接结果与读到的内容，用于验证「不建 VPN 也能直连控制端」。
func (n *Node) ProbeTCP(addr string, payload []byte, timeoutSec int) (string, error) {
	defer logPanic("ProbeTCP")
	if n == nil || n.srv == nil {
		return "", errors.New("tsnetpoc: 节点未初始化")
	}
	if timeoutSec <= 0 {
		timeoutSec = 15
	}
	dialCtx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSec)*time.Second)
	defer cancel()

	conn, err := n.srv.Dial(dialCtx, "tcp", addr)
	if err != nil {
		return "", fmt.Errorf("tsnetpoc: 拨号 %s 失败: %w", addr, err)
	}
	defer conn.Close()

	_ = conn.SetWriteDeadline(time.Now().Add(time.Duration(timeoutSec) * time.Second))
	if len(payload) > 0 {
		if _, err := conn.Write(payload); err != nil {
			return "", fmt.Errorf("tsnetpoc: 写入失败: %w", err)
		}
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 4096)
	rn, rerr := conn.Read(buf)
	result := map[string]any{
		"addr":       addr,
		"connected":  true,
		"read_bytes": rn,
		"read_error": "",
		"response":   string(buf[:rn]),
	}
	if rerr != nil {
		// 读超时/对端未回包不视为连接失败（本打样的目的是验证隧道可达）
		result["read_error"] = rerr.Error()
	}
	b, _ := json.Marshal(result)
	return string(b), nil
}

// StartDialProxy 在 127.0.0.1 上监听随机端口，返回 "127.0.0.1:port"。
// 每个本地连接都会被转换成一次 tsnet Dial 到 target（例如 100.x.x.x:56789），
// 双向转发字节流。Kotlin 侧继续用普通 java.net.Socket 连本地端口即可，
// 从而在不建 VPN 的前提下保留原有帧协议与收发逻辑。
func (n *Node) StartDialProxy(target string) (string, error) {
	defer logPanic("StartDialProxy")
	if n == nil || n.srv == nil {
		return "", errors.New("tsnetpoc: 节点未初始化")
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", fmt.Errorf("tsnetpoc: 本地监听失败: %w", err)
	}
	n.mu.Lock()
	n.closers = append(n.closers, ln)
	n.mu.Unlock()

	go func() {
		for {
			lc, err := ln.Accept()
			if err != nil {
				return
			}
			go n.proxyToTarget(lc, target)
		}
	}()
	log.Printf("[tsnetpoc] 拨号代理就绪: %s → %s", ln.Addr().String(), target)
	return ln.Addr().String(), nil
}

// proxyToTarget 把一条本地连接转成 tsnet 拨号连接并双向转发。
func (n *Node) proxyToTarget(local net.Conn, target string) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	remote, err := n.srv.Dial(ctx, "tcp", target)
	if err != nil {
		log.Printf("[tsnetpoc] 代理拨号 %s 失败: %v", target, err)
		_ = local.Close()
		return
	}
	pipe(local, remote)
}

// StartListenProxy 在尾网上监听 tsPort，收到连接后转发到本地 localAddr
// （例如 "127.0.0.1:56786"）。用于保留 RelayServerListener 的入站直连能力：
// 控制端连 100.x:56786 → tsnet 接受 → 转发进本机真实 ServerSocket。
func (n *Node) StartListenProxy(tsPort int, localAddr string) (string, error) {
	defer logPanic("StartListenProxy")
	if n == nil || n.srv == nil {
		return "", errors.New("tsnetpoc: 节点未初始化")
	}
	if tsPort <= 0 || localAddr == "" {
		return "", errors.New("tsnetpoc: tsPort/localAddr 非法")
	}
	ln, err := n.srv.Listen("tcp", fmt.Sprintf(":%d", tsPort))
	if err != nil {
		return "", fmt.Errorf("tsnetpoc: 尾网监听 %d 失败: %w", tsPort, err)
	}
	n.mu.Lock()
	if n.listeners == nil {
		n.listeners = make(map[int]net.Listener)
	}
	// 同端口重复 expose：先关旧 listener，避免"Leak"式端口占用（Kotlin 侧虽幂等，仍兜一层）
	if old, ok := n.listeners[tsPort]; ok && old != nil {
		_ = old.Close()
	}
	n.listeners[tsPort] = ln
	n.closers = append(n.closers, ln)
	n.mu.Unlock()

	go func() {
		for {
			rc, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				local, err := net.DialTimeout("tcp", localAddr, 5*time.Second)
				if err != nil {
					log.Printf("[tsnetpoc] 转发到本地 %s 失败: %v", localAddr, err)
					_ = c.Close()
					return
				}
				pipe(c, local)
			}(rc)
		}
	}()
	log.Printf("[tsnetpoc] 入站代理就绪: 尾网 :%d → %s", tsPort, localAddr)
	return fmt.Sprintf(":%d", tsPort), nil
}

// StopListenProxy 回收指定尾网端口的入站代理（幂等）。
// ★ 2026-11-06：会话结束后必须能回收，否则端口长期监听、连接转入失败分支。
// 注意：关闭 listener 会让该端口上已建立的连接在下次读写时报错（符合"会话结束"语义）。
func (n *Node) StopListenProxy(tsPort int) error {
	defer logPanic("StopListenProxy")
	if n == nil || tsPort <= 0 {
		return nil
	}
	n.mu.Lock()
	ln := n.listeners[tsPort]
	delete(n.listeners, tsPort)
	n.mu.Unlock()
	if ln == nil {
		return nil
	}
	if err := ln.Close(); err != nil {
		return fmt.Errorf("tsnetpoc: 关闭尾网监听 %d 失败: %w", tsPort, err)
	}
	log.Printf("[tsnetpoc] 入站代理已回收: 尾网 :%d", tsPort)
	return nil
}

// pipe 双向复制字节流，任一端结束即关闭两端。
func pipe(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(a, b)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(b, a)
		done <- struct{}{}
	}()
	<-done
	_ = a.Close()
	_ = b.Close()
	<-done
}

// Close 关闭节点、所有本地代理监听并释放 netstack。
func (n *Node) Close() error {
	defer logPanic("Close")
	if n == nil || n.srv == nil {
		return nil
	}
	n.mu.Lock()
	for _, c := range n.closers {
		_ = c.Close()
	}
	n.closers = nil
	n.listeners = nil
	n.mu.Unlock()
	return n.srv.Close()
}
