@echo off
chcp 936 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"
title SmsForwarder 远程控制APK打包安装工具

echo ============================================================
echo        SmsForwarder 远程控制 APK 打包安装工具
echo ============================================================
echo.

rem ================= 1. 菜单选择打包类型 =================
echo 正在弹出对话框，请选择打包类型...
> "%TEMP%\sf_menu.vbs" echo WScript.Echo MsgBox("请选择要打包的类型：" + vbCrLf + vbCrLf + "是(Y) = 打包控制端。" + vbCrLf + "否(N) = 打包被控端。" + vbCrLf + "取消 = 退出", 35, "SmsForwarder 打包选择")
set "APP_MODE="
for /f "delims=" %%i in ('cscript //nologo "%TEMP%\sf_menu.vbs"') do set "APP_MODE=%%i"
del "%TEMP%\sf_menu.vbs" >nul 2>&1

if "%APP_MODE%"=="6" (
    set "MODE_TAG=controller"
    set "MODE_NAME=控制端"
)
if "%APP_MODE%"=="7" (
    set "MODE_TAG=server"
    set "MODE_NAME=被控端"
)
if not defined MODE_TAG (
    echo 已取消，程序退出。
    pause
    exit /b 0
)
echo.
echo 已选择：打包%MODE_NAME%（%MODE_TAG%）
echo.

rem ================= 2. 配置Java环境 =================
set "JAVA_HOME=E:\Andorid Studio\jdk-17.0.2"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 未找到JDK：%JAVA_HOME%
    echo 请修改脚本中的 JAVA_HOME 路径。
    pause
    exit /b 1
)
echo JDK路径：%JAVA_HOME%
echo.

rem ================= 3. 开始打包 =================
echo [1/5] Gradle 开始打包 %MODE_NAME% APK，约需1~3分钟，请稍候...
set "APK_DIR=build\app\outputs\apk\debug"
if not exist "%APK_DIR%" set "APK_DIR=app\build\outputs\apk\debug"
rem 打包日志先落盘以保留退出码，再过滤显示（避免管道丢失退出码和 R8 警告刷屏）
call .\gradlew.bat assembleDebug -PappMode=%MODE_TAG% --console=plain > "%TEMP%\sf_build_%MODE_TAG%.log" 2>&1
set "BUILD_CODE=%ERRORLEVEL%"
echo.
echo -------------------- 打包过程输出（已过滤良性警告） --------------------
type "%TEMP%\sf_build_%MODE_TAG%.log" | findstr /v /c:"R8: Expected stack map table" /c:"In later version of R8"
echo -----------------------------------------------------------------------
del "%TEMP%\sf_build_%MODE_TAG%.log" >nul 2>&1
if not "%BUILD_CODE%"=="0" (
    echo [错误] 打包失败，请查看上方错误信息。
    pause
    exit /b 1
)
echo [1/5] 打包成功！
echo.

rem ================= 4. 定位APK文件 =================
echo [2/5] 定位生成的APK文件 ...
set "APK_FILE="
for /f "delims=" %%f in ('dir /b "%APK_DIR%\*universal*.apk" 2^>nul') do set "APK_FILE=%%f"
if not defined APK_FILE (
    for /f "delims=" %%f in ('dir /b "%APK_DIR%\*.apk" 2^>nul') do set "APK_FILE=%%f"
)
if not defined APK_FILE (
    echo [错误] 未找到生成的APK文件，请检查 %APK_DIR% 目录。
    pause
    exit /b 1
)
set "OUT_APK=%cd%\SmsForwarder_%MODE_NAME%.apk"
copy /y "%APK_DIR%\%APK_FILE%" "%OUT_APK%" >nul
echo [2/5] APK已保存：%OUT_APK%
echo.

rem ================= 5. 检测手机设备 =================
echo [3/5] 检测已连接的手机设备...
rem 查找 adb 可执行文件：优先常见 SDK 路径（避免 PATH 中旧版本 adb 干扰），PATH 作为后备
set "ADB="
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB if exist "C:\Users\%USERNAME%\AppData\Local\Android\Sdk\platform-tools\adb.exe" set "ADB=C:\Users\%USERNAME%\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "C:\Android\Sdk\platform-tools\adb.exe" set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
if not defined ADB (
    where adb.exe >nul 2>&1
    if not errorlevel 1 set "ADB=adb.exe"
)
if not defined ADB (
    echo [错误] 未找到 adb 工具，请安装 Android SDK Platform-Tools。
    pause
    exit /b 1
)
echo 使用adb：%ADB%
echo.

rem 第一轮检测
call :scan_devices
if %DEVICE_COUNT% GTR 0 goto devices_ok

rem 未检测到设备：重置 adb 服务后重试（解决 adb 服务异常/端口占用导致的检测失败）
echo.
echo 未检测到设备，正在重置 adb 服务后重新检测...
"%ADB%" kill-server >nul 2>&1
"%ADB%" start-server >nul 2>&1
call :scan_devices

if %DEVICE_COUNT% EQU 0 (
    if %UNAUTHORIZED_COUNT% GTR 0 (
        echo [错误] 检测到 %UNAUTHORIZED_COUNT% 台设备未授权。
        echo 请解锁手机，在弹出的"允许USB调试"窗口中点击"允许"，然后重新运行本脚本。
    ) else if %OFFLINE_COUNT% GTR 0 (
        echo [错误] 检测到 %OFFLINE_COUNT% 台设备离线（offline）。
        echo 请重新插拔数据线，并在手机上选择"文件传输"模式后重试。
    ) else (
        echo [错误] 未检测到已连接的手机设备。
        echo 请确认：1.手机已开启"开发者选项-USB调试" 2.使用数据线连接电脑
        echo         3.手机连接方式选择"文件传输/MTP" 4.授权窗口已点"允许"。
    )
    pause
    exit /b 1
)

:devices_ok
echo [3/5] 检测到 %DEVICE_COUNT% 台设备，准备安装。
echo.

rem ================= 6. 安装APK =================
echo [4/5] 安装APK到手机...
"%ADB%" install -r "%OUT_APK%"
if errorlevel 1 (
    echo [错误] 安装失败。
    pause
    exit /b 1
)
echo [4/5] 安装成功！
echo.

rem ================= 7. 启动验证 =================
echo [5/5] 启动应用并验证运行状态...
"%ADB%" shell am force-stop cn.ppps.forwarder
"%ADB%" shell am start -n cn.ppps.forwarder/.activity.SplashActivity
timeout /t 8 /nobreak >nul
set "PID="
for /f "delims=" %%p in ('"%ADB%" shell pidof cn.ppps.forwarder') do set "PID=%%p"
if not defined PID (
    echo [验证失败] 应用未在运行，请检查是否崩溃。
    pause
    exit /b 1
)
echo [5/5] 应用已启动，进程ID：%PID%
echo [5/5] 中继连接日志（最近30条）：
"%ADB%" logcat -d -t 30 | findstr /i "Relay"
echo.
echo ============================================================
echo   全部完成：%MODE_NAME% APK 已打包并安装到手机！
echo ============================================================
pause
exit /b 0

rem ================= 设备扫描子程序 =================
:scan_devices
set "DEVICE_COUNT=0"
set "UNAUTHORIZED_COUNT=0"
set "OFFLINE_COUNT=0"
echo 当前设备列表：
"%ADB%" devices
for /f "skip=1 delims=" %%d in ('"%ADB%" devices') do (
    set "LINE=%%d"
    if not "!LINE!"=="" (
        echo !LINE! | findstr /c:"device" >nul
        if not errorlevel 1 set /a DEVICE_COUNT+=1
        echo !LINE! | findstr /c:"unauthorized" >nul
        if not errorlevel 1 set /a UNAUTHORIZED_COUNT+=1
        echo !LINE! | findstr /c:"offline" >nul
        if not errorlevel 1 set /a OFFLINE_COUNT+=1
    )
)
exit /b 0
