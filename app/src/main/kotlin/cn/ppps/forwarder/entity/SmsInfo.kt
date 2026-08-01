package cn.ppps.forwarder.entity

import cn.ppps.forwarder.R
import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class SmsInfo(
    //联系人姓名
    var name: String = "",
    //号码
    var number: String = "",
    //短信内容
    var content: String = "",
    //短信时间
    var date: Long = 0L,
    //短信类型: 1=接收, 2=发送
    var type: Int = 1,
    //卡槽ID： 0=Sim1, 1=Sim2, -1=获取失败
    @SerializedName("sim_id")
    var simId: Int = -1,
    //卡槽主键
    @SerializedName("sub_id")
    var subId: Int = 0,
) : Serializable {

    val typeImageId: Int
        get() {
            return R.drawable.ic_sms
        }

    val simImageId: Int
        get() {
            return when (simId) {
                0 -> R.drawable.ic_sim1
                1 -> R.drawable.ic_sim2
                else -> R.drawable.ic_sim
            }
        }

    override fun toString(): String {
        return "SmsInfo{" +
                "name='" + name + '\'' +
                ", number='" + number + '\'' +
                ", content='" + content + '\'' +
                ", date=" + date +
                ", type=" + type +
                ", simId=" + simId +
                '}'
    }
}
