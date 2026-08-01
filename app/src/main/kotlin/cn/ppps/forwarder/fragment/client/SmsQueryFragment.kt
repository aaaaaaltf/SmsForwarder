package cn.ppps.forwarder.fragment.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.android.vlayout.DelegateAdapter
import com.alibaba.android.vlayout.VirtualLayoutManager
import com.alibaba.android.vlayout.layout.LinearLayoutHelper
import com.google.gson.reflect.TypeToken
import cn.ppps.forwarder.R
import cn.ppps.forwarder.adapter.base.broccoli.BroccoliSimpleDelegateAdapter
import cn.ppps.forwarder.adapter.base.delegate.SimpleDelegateAdapter
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientSmsQueryBinding
import cn.ppps.forwarder.entity.SmsInfo
import cn.ppps.forwarder.relay.RelayApi
import cn.ppps.forwarder.relay.RelayClientHolder
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.server.model.SmsQueryData
import cn.ppps.forwarder.utils.DataProvider.emptySmsInfo
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
import com.xuexiang.xui.widget.searchview.MaterialSearchView.SearchViewListener
import com.xuexiang.xutil.data.DateUtils
import com.xuexiang.xutil.resource.ResUtils.getColor
import com.xuexiang.xutil.resource.ResUtils.getStringArray
import me.samlss.broccoli.Broccoli

@Suppress("PrivatePropertyName")
@Page(name = "远程查短信")
class SmsQueryFragment : BaseFragment<FragmentClientSmsQueryBinding?>() {

    private val TAG: String = SmsQueryFragment::class.java.simpleName
    private var mAdapter: SimpleDelegateAdapter<SmsInfo>? = null
    private var smsType: Int = 1
    private var pageNum: Int = 1
    private val pageSize: Int = 20
    private var keyword: String = ""

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientSmsQueryBinding {
        return FragmentClientSmsQueryBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.api_sms_query)
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

        mAdapter = object : BroccoliSimpleDelegateAdapter<SmsInfo>(
            R.layout.adapter_sms_card_view_list_item,
            LinearLayoutHelper(),
            emptySmsInfo
        ) {
            override fun onBindData(
                holder: RecyclerViewHolder,
                model: SmsInfo,
                position: Int,
            ) {
                holder.text(R.id.tv_from, model.number)
                holder.text(R.id.tv_time, DateUtils.getFriendlyTimeSpanByNow(model.date))
                holder.image(R.id.iv_image, model.typeImageId)
                holder.image(R.id.iv_sim_image, model.simImageId)
                holder.text(R.id.tv_content, model.content)
                holder.click(R.id.iv_reply) {
                    XToastUtils.info(getString(R.string.local_call) + model.number)
                    PhoneUtils.dial(model.number)
                }
            }

            override fun onBindBroccoli(holder: RecyclerViewHolder, broccoli: Broccoli) {
                broccoli.addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.tv_from)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.tv_time)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.iv_sim_image)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.tv_content)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.iv_image)))
                    .addPlaceholder(PlaceholderHelper.getParameter(holder.findView(R.id.iv_reply)))
            }

        }

        val delegateAdapter = DelegateAdapter(virtualLayoutManager)
        delegateAdapter.addAdapter(mAdapter)
        binding!!.recyclerView.adapter = delegateAdapter

        binding!!.tabBar.setTabTitles(getStringArray(R.array.sms_type_option))
        binding!!.tabBar.setOnTabClickListener { _, position ->
            smsType = position + 1
            loadRemoteData(true)
            binding!!.recyclerView.scrollToPosition(0)
        }

        //搜索框
        binding!!.searchView.findViewById<View>(com.xuexiang.xui.R.id.search_layout).visibility = View.GONE
        binding!!.searchView.setEllipsize(true)
        binding!!.searchView.setOnQueryTextListener(object : MaterialSearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                SnackbarUtils.Indefinite(view, String.format(getString(R.string.search_keyword), query)).info()
                    .actionColor(getColor(R.color.xui_config_color_white))
                    .setAction(getString(R.string.clear)) {
                        keyword = ""
                        loadRemoteData(true)
                    }.show()
                if (keyword != query) {
                    keyword = query
                    loadRemoteData(true)
                }
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                return false
            }
        })
        binding!!.searchView.setOnSearchViewListener(object : SearchViewListener {
            override fun onSearchViewShown() {}
            override fun onSearchViewClosed() {}
        })
        binding!!.searchView.setSubmitOnClick(true)
    }

    override fun initListeners() {
        //下拉刷新
        binding!!.refreshLayout.setOnRefreshListener { refreshLayout: RefreshLayout ->
            refreshLayout.layout.postDelayed({
                loadRemoteData(true)
            }, 1000)
        }
        //上拉加载
        binding!!.refreshLayout.setOnLoadMoreListener { refreshLayout: RefreshLayout ->
            refreshLayout.layout.postDelayed({
                loadRemoteData(false)
            }, 1000)
        }
        binding!!.refreshLayout.autoRefresh() //第一次进入触发自动刷新，演示效果
    }

    private fun loadRemoteData(refresh: Boolean) {
        if (refresh) pageNum = 1
        val queryData = SmsQueryData(smsType, pageNum, pageSize, keyword)

        RelayApi.request(RelayCommands.CMD_SMS_QUERY, RelayClientHolder.gson.toJson(queryData), RelayCommands.RSP_SMS_QUERY, { json ->
            try {
                val resp = RelayClientHolder.gson.fromJson<BaseResponse<List<SmsInfo>?>>(json, object : TypeToken<BaseResponse<List<SmsInfo>?>>() {}.type)
                if (resp.code == 200) {
                    pageNum++
                    if (refresh) {
                        mAdapter!!.refresh(resp.data)
                        binding!!.refreshLayout.finishRefresh()
                        binding!!.recyclerView.scrollToPosition(0)
                    } else {
                        mAdapter!!.loadMore(resp.data)
                        binding!!.refreshLayout.finishLoadMore()
                    }
                } else {
                    XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                    if (refresh) binding!!.refreshLayout.finishRefresh() else binding!!.refreshLayout.finishLoadMore()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "onSuccess error: ${e.message}")
                XToastUtils.error(getString(R.string.request_failed) + e.message)
                if (refresh) binding!!.refreshLayout.finishRefresh() else binding!!.refreshLayout.finishLoadMore()
            }
        }, { msg ->
            XToastUtils.error(getString(R.string.request_failed) + msg)
            if (refresh) binding!!.refreshLayout.finishRefresh() else binding!!.refreshLayout.finishLoadMore()
        })
    }

}
