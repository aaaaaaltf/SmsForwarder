package cn.ppps.forwarder.fragment.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.isDigitsOnly
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.android.vlayout.DelegateAdapter
import com.alibaba.android.vlayout.VirtualLayoutManager
import com.alibaba.android.vlayout.layout.LinearLayoutHelper
import com.google.gson.reflect.TypeToken
import cn.ppps.forwarder.R
import cn.ppps.forwarder.adapter.base.broccoli.BroccoliSimpleDelegateAdapter
import cn.ppps.forwarder.adapter.base.delegate.SimpleDelegateAdapter
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientContactQueryBinding
import cn.ppps.forwarder.entity.ContactInfo
import cn.ppps.forwarder.relay.RelayApi
import cn.ppps.forwarder.relay.RelayClientHolder
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.server.model.ContactQueryData
import cn.ppps.forwarder.utils.DataProvider
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.PlaceholderHelper
import cn.ppps.forwarder.utils.XToastUtils
import com.scwang.smartrefresh.layout.api.RefreshLayout
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xui.adapter.recyclerview.RecyclerViewHolder
import com.xuexiang.xui.utils.SnackbarUtils
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xui.widget.searchview.MaterialSearchView
import com.xuexiang.xutil.resource.ResUtils.getColor
import com.xuexiang.xutil.system.ClipboardUtils
import me.samlss.broccoli.Broccoli

@Suppress("PrivatePropertyName")
@Page(name = "远程查话簿")
class ContactQueryFragment : BaseFragment<FragmentClientContactQueryBinding?>() {

    private val TAG: String = ContactQueryFragment::class.java.simpleName
    private var mAdapter: SimpleDelegateAdapter<ContactInfo>? = null
    private var keyword: String = ""

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientContactQueryBinding {
        return FragmentClientContactQueryBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.api_contact_query)
        titleBar!!.addAction(object : TitleBar.ImageAction(R.drawable.ic_query) {
            @SingleClick
            override fun performAction(view: View) {
                binding!!.searchView.showSearch()
            }
        })
        return titleBar
    }

    /**
     * 初始化控件
     */
    override fun initViews() {
        val virtualLayoutManager = VirtualLayoutManager(requireContext())
        binding!!.recyclerView.layoutManager = virtualLayoutManager
        val viewPool = RecyclerView.RecycledViewPool()
        binding!!.recyclerView.setRecycledViewPool(viewPool)
        viewPool.setMaxRecycledViews(0, 10)

        mAdapter = object : BroccoliSimpleDelegateAdapter<ContactInfo>(
            R.layout.adapter_contact_card_view_list_item,
            LinearLayoutHelper(),
            DataProvider.emptyContactInfo
        ) {
            override fun onBindData(
                holder: RecyclerViewHolder,
                model: ContactInfo,
                position: Int,
            ) {
                holder.text(R.id.sb_letter, model.firstLetter)
                holder.text(R.id.tv_name, model.name)
                holder.text(R.id.tv_phone_number, model.phoneNumber)

                holder.click(R.id.iv_copy) {
                    val str = model.toString()
                    XToastUtils.info(String.format(getString(R.string.copied_to_clipboard), str))
                    ClipboardUtils.copyText(str)
                }
                holder.click(R.id.iv_call) {
                    XToastUtils.info(getString(R.string.local_call) + model.phoneNumber)
                    PhoneUtils.dial(model.phoneNumber)
                }
            }

            override fun onBindBroccoli(holder: RecyclerViewHolder, broccoli: Broccoli) {
                broccoli.addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.tv_name)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.tv_phone_number)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.sb_letter)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.iv_copy)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.iv_call)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.iv_reply)))
            }

        }

        val delegateAdapter = DelegateAdapter(virtualLayoutManager)
        delegateAdapter.addAdapter(mAdapter)
        binding!!.recyclerView.adapter = delegateAdapter

        //搜索框
        binding!!.searchView.findViewById<View>(com.xuexiang.xui.R.id.search_layout).visibility = View.GONE
        binding!!.searchView.setEllipsize(true)
        binding!!.searchView.setOnQueryTextListener(object : MaterialSearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                SnackbarUtils.Indefinite(view, String.format(getString(R.string.search_keyword), query)).info()
                    .actionColor(getColor(R.color.xui_config_color_white))
                    .setAction(getString(R.string.clear)) {
                        keyword = ""
                        loadRemoteData()
                    }.show()
                if (keyword != query) {
                    keyword = query
                    loadRemoteData()
                }
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                return false
            }
        })
        binding!!.searchView.setOnSearchViewListener(object : MaterialSearchView.SearchViewListener {
            override fun onSearchViewShown() {}
            override fun onSearchViewClosed() {}
        })
        binding!!.searchView.setSubmitOnClick(true)
    }

    override fun initListeners() {
        //下拉刷新
        binding!!.refreshLayout.setOnRefreshListener { refreshLayout: RefreshLayout ->
            refreshLayout.layout.postDelayed({
                loadRemoteData()
            }, 1000)
        }
        binding!!.refreshLayout.autoRefresh() //第一次进入触发自动刷新，演示效果
    }

    private fun loadRemoteData() {
        val queryData = if (keyword.isDigitsOnly())
            ContactQueryData(1, 20, keyword, null)
        else
            ContactQueryData(1, 20, null, keyword)

        RelayApi.request(RelayCommands.CMD_CONTACT_QUERY, RelayClientHolder.gson.toJson(queryData), RelayCommands.RSP_CONTACT_QUERY, { json ->
            try {
                val resp = RelayClientHolder.gson.fromJson<BaseResponse<List<ContactInfo>?>>(json, object : TypeToken<BaseResponse<List<ContactInfo>?>>() {}.type)
                if (resp.code == 200) {
                    mAdapter!!.refresh(resp.data)
                    binding!!.refreshLayout.finishRefresh()
                    binding!!.recyclerView.scrollToPosition(0)
                } else {
                    XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                    binding!!.refreshLayout.finishRefresh()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "onSuccess error: ${e.message}")
                XToastUtils.error(getString(R.string.request_failed) + e.message)
                binding!!.refreshLayout.finishRefresh()
            }
        }, { msg ->
            XToastUtils.error(getString(R.string.request_failed) + msg)
            binding!!.refreshLayout.finishRefresh()
        })
    }

}
