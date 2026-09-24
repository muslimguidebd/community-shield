package com.muslimguide.shield

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

object ShieldToast {

    fun showSuccess(
        context: Context,
        message: String,
        title: String = "সফল",
        gravity: Int = Gravity.BOTTOM,
        yOffset: Int = 120
    ) {
        showCustom(
            context = context,
            message = message,
            title = title,
            iconRes = R.drawable.ic_privacy_shield,
            backgroundColor = Color.parseColor("#0F766E"),
            textColor = Color.WHITE,
            gravity = gravity,
            yOffset = yOffset
        )
    }

    fun showError(
        context: Context,
        message: String,
        title: String = "ত্রুটি",
        gravity: Int = Gravity.BOTTOM,
        yOffset: Int = 120
    ) {
        showCustom(
            context = context,
            message = message,
            title = title,
            iconRes = R.drawable.ic_privacy_shield,
            backgroundColor = Color.parseColor("#DC2626"),
            textColor = Color.WHITE,
            gravity = gravity,
            yOffset = yOffset
        )
    }

    fun showWarning(
        context: Context,
        message: String,
        title: String = "সতর্কতা",
        gravity: Int = Gravity.BOTTOM,
        yOffset: Int = 120
    ) {
        showCustom(
            context = context,
            message = message,
            title = title,
            iconRes = R.drawable.ic_privacy_shield,
            backgroundColor = Color.parseColor("#D97706"),
            textColor = Color.WHITE,
            gravity = gravity,
            yOffset = yOffset
        )
    }

    fun showInfo(
        context: Context,
        message: String,
        title: String = "তথ্য",
        gravity: Int = Gravity.BOTTOM,
        yOffset: Int = 120
    ) {
        showCustom(
            context = context,
            message = message,
            title = title,
            iconRes = R.drawable.ic_privacy_shield,
            backgroundColor = Color.parseColor("#2563EB"),
            textColor = Color.WHITE,
            gravity = gravity,
            yOffset = yOffset
        )
    }

    private fun showCustom(
        context: Context,
        message: String,
        title: String? = null,
        iconRes: Int = R.drawable.ic_privacy_shield,
        backgroundColor: Int = Color.parseColor("#0F766E"),
        textColor: Int = Color.WHITE,
        gravity: Int = Gravity.BOTTOM,
        xOffset: Int = 0,
        yOffset: Int = 120,
        duration: Int = Toast.LENGTH_SHORT
    ) {
        try {
            val view = LayoutInflater.from(context).inflate(R.layout.layout_shield_toast, null)
            val toastRoot = view.findViewById<View>(R.id.toastRoot)
            val toastIcon = view.findViewById<ImageView>(R.id.toastIcon)
            val toastTitle = view.findViewById<TextView>(R.id.toastTitle)
            val toastMessage = view.findViewById<TextView>(R.id.toastMessage)

            val backgroundDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * context.resources.displayMetrics.density
                setColor(backgroundColor)
            }
            toastRoot.background = backgroundDrawable

            toastIcon.setImageResource(iconRes)

            if (!title.isNullOrEmpty()) {
                toastTitle.text = title
                toastTitle.visibility = View.VISIBLE
                toastTitle.setTextColor(textColor)
            } else {
                toastTitle.visibility = View.GONE
            }

            toastMessage.text = message
            toastMessage.setTextColor(textColor)

            val toast = Toast(context.applicationContext)
            toast.setGravity(gravity, xOffset, yOffset)
            toast.duration = duration
            toast.view = view
            toast.show()
        } catch (e: Exception) {
            Toast.makeText(context, message, duration).show()
        }
    }
}