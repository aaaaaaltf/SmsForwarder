package cn.ppps.forwarder.utils

import android.graphics.drawable.GradientDrawable

/**
 * Drawable工具类
 */
@Suppress("SameParameterValue", "unused")
class DrawableUtils private constructor() {
    companion object {

        /**
         * 创建圆形的drawable
         */
        fun createOvalDrawable(color: Int): GradientDrawable {
            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(color)
            return drawable
        }

        /**
         * 创建矩形的drawable
         */
        fun createRectangleDrawable(color: Int, cornerRadius: Float): GradientDrawable {
            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.RECTANGLE
            drawable.setColor(color)
            drawable.cornerRadius = cornerRadius
            return drawable
        }
    }

    init {
        throw UnsupportedOperationException("u can't instantiate me...")
    }
}
