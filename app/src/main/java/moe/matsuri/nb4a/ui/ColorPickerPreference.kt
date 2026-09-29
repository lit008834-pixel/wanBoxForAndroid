// @author 雾晚
package moe.matsuri.nb4a.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.NestedScrollView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

class ColorPickerPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    )
) : Preference(context, attrs, defStyle) {

    data class Selection(val themeId: Int, val customColor: Int? = null)

    private val presetIds = intArrayOf(
        Theme.RED, Theme.PINK_SSR, Theme.PINK, Theme.PURPLE, Theme.DEEP_PURPLE,
        Theme.INDIGO, Theme.BLUE, Theme.LIGHT_BLUE, Theme.CYAN, Theme.TEAL,
        Theme.GREEN, Theme.LIGHT_GREEN, Theme.LIME, Theme.YELLOW, Theme.AMBER,
        Theme.ORANGE, Theme.DEEP_ORANGE, Theme.BROWN, Theme.GREY, Theme.BLUE_GREY,
        Theme.BLACK, Theme.VERDANT_MINT
    )

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout
        widgetFrame.removeAllViews()
        val color = when (DataStore.appTheme) {
            Theme.BLACK -> Color.BLACK
            Theme.WHITE -> Color.WHITE
            Theme.LIGHT_GRAY -> context.getColor(R.color.color_light_gray_bg)
            Theme.CUSTOM -> Theme.customPrimaryColor()
            else -> Theme.getPrimaryColor(context)
        }
        widgetFrame.addView(ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(dp2px(40), dp2px(40))
            setImageDrawable(colorBadge(context.resources, color, false))
            contentDescription = context.getString(R.string.theme_current_color)
        })
        widgetFrame.visibility = View.VISIBLE
    }

    private fun colorBadge(resources: Resources, color: Int, selected: Boolean): Drawable {
        val contrast = if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp2px(if (selected) 3 else 1), if (selected) contrast else 0x33000000)
        }
        if (!selected) return circle
        val check = ResourcesCompat.getDrawable(
            resources, R.drawable.ic_baseline_check_circle_24, null
        )!!.mutate()
        DrawableCompat.setTint(check, contrast)
        return LayerDrawable(arrayOf(circle, check)).apply {
            val inset = dp2px(10)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(context.getColorAttr(android.R.attr.textColorPrimary))
        setPadding(dp2px(2), dp2px(12), 0, dp2px(8))
    }

    private fun swatch(name: String, color: Int, selected: Boolean, onClick: () -> Unit) =
        FrameLayout(context).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = dp2px(52)
                height = dp2px(52)
            }
            isClickable = true
            isFocusable = true
            contentDescription = if (selected) {
                context.getString(R.string.theme_color_selected_description, name)
            } else name
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp2px(38), dp2px(38), Gravity.CENTER)
                setImageDrawable(colorBadge(context.resources, color, selected))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }

    private fun customSwatch(selected: Boolean, onClick: () -> Unit) = FrameLayout(context).apply {
        layoutParams = GridLayout.LayoutParams().apply {
            width = dp2px(52)
            height = dp2px(52)
        }
        isClickable = true
        isFocusable = true
        val name = context.getString(R.string.theme_custom_entry)
        contentDescription = if (selected) {
            context.getString(R.string.theme_color_selected_description, name)
        } else name
        setOnClickListener { onClick() }
        addView(View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp2px(40), dp2px(40), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(
                    dp2px(2), context.getColorAttr(R.attr.colorPrimary),
                    dp2px(4).toFloat(), dp2px(3).toFloat()
                )
            }
        })
        if (selected) {
            addView(ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp2px(26), dp2px(26), Gravity.CENTER)
                setImageDrawable(colorBadge(context.resources, Theme.customPrimaryColor(), true))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        } else {
            addView(TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp2px(36), dp2px(36), Gravity.CENTER)
                text = context.getString(R.string.theme_custom_add_symbol)
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(context.getColorAttr(R.attr.colorPrimary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
    }

    private fun showCustomColorDialog(paletteDialog: AlertDialog) {
        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp2px(60), dp2px(60)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp2px(12)
            }
            contentDescription = context.getString(R.string.theme_custom_preview)
        }
        fun showPreview(color: Int) {
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp2px(1), 0x33000000)
            }
        }
        showPreview(Theme.customPrimaryColor())

        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(7))
            setSingleLine(true)
            setSelectAllOnFocus(true)
            hint = context.getString(R.string.custom_color_hex_hint)
            setText(ThemeColorInput.format(Theme.customPrimaryColor()))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    ThemeColorInput.parse(s?.toString().orEmpty())?.let { showPreview(it) }
                    error = null
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(24), dp2px(12), dp2px(24), dp2px(8))
            addView(preview)
            addView(input)
        }
        val customDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.custom_color_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.custom_color_apply, null)
            .show()
        customDialog.window?.decorView?.let(Theme::tintCustomViews)
        customDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val color = ThemeColorInput.parse(input.text.toString())
            if (color == null) {
                input.error = context.getString(R.string.theme_invalid_hex_color)
                return@setOnClickListener
            }
            if (callChangeListener(Selection(Theme.CUSTOM, color))) {
                customDialog.dismiss()
                paletteDialog.dismiss()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val currentId = DataStore.appTheme
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(16), dp2px(4), dp2px(16), dp2px(16))
        }
        val scroll = NestedScrollView(context).apply {
            addView(root)
        }
        lateinit var dialog: AlertDialog
        fun select(id: Int) {
            if (callChangeListener(Selection(id))) dialog.dismiss()
        }

        root.addView(sectionTitle(context.getString(R.string.theme_preset_colors)))
        val grid = object : GridLayout(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w > 0) {
                    val columns = (w / dp2px(52)).coerceIn(1, 8)
                    if (columnCount != columns) columnCount = columns
                    val inset = ((w - columns * dp2px(52)) / 2).coerceAtLeast(0)
                    setPadding(inset, 0, inset, 0)
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = GridLayout.HORIZONTAL
            columnCount = 5
        }
        val names = context.resources.getStringArray(R.array.theme_preset_names)
        val colors = context.resources.obtainTypedArray(R.array.material_colors)
        try {
            presetIds.forEachIndexed { index, id ->
                val color = colors.getColor(index, Color.BLACK)
                grid.addView(swatch(names[index], color, currentId == id) { select(id) })
            }
        } finally {
            colors.recycle()
        }
        grid.addView(customSwatch(currentId == Theme.CUSTOM) { showCustomColorDialog(dialog) })
        root.addView(grid)

        root.addView(sectionTitle(context.getString(R.string.theme_background_modes)))
        val modes = mutableListOf(
            Theme.WHITE to R.string.theme_mode_white,
            Theme.LIGHT_GRAY to R.string.theme_mode_light_gray,
            Theme.BLACK to R.string.theme_mode_black
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modes.add(Theme.MONET to R.string.theme_mode_monet)
        }
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            modes.forEach { (id, labelRes) ->
                val selected = currentId == id
                addView(TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp2px(56), 1f).apply {
                        marginEnd = dp2px(4)
                    }
                    text = context.getString(labelRes)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (selected) {
                        context.getString(R.string.theme_color_selected_description, text)
                    } else text
                    setTextColor(if (selected) Theme.getPrimaryColor(context) else context.getColorAttr(android.R.attr.textColorPrimary))
                    background = GradientDrawable().apply {
                        cornerRadius = dp2px(12).toFloat()
                        setColor(context.getColorAttr(R.attr.colorSurface))
                        setStroke(dp2px(if (selected) 2 else 1), if (selected) Theme.getPrimaryColor(context) else 0x33000000)
                    }
                    setOnClickListener { select(id) }
                })
            }
        })

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.theme)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.decorView?.let(Theme::tintCustomViews)
    }
}
