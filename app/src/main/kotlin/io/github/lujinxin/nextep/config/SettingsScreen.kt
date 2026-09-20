package io.github.lujinxin.nextep.config

import android.app.StatusBarManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.trigger.NeXtepTileService

object SettingsScreen {
    private data class AppOption(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )

    fun create(activity: AppCompatActivity, repository: SettingsRepository): View {
        val settings = repository.topBarSettings()
        val apps = loadApps(activity)
        val appByName = apps.associateBy { it.component.flattenToString() }
        val selected = settings.appComponents.filter(appByName::containsKey).toMutableList()
        var manualMode = settings.manualAppOrder

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 22), dp(activity, 20), dp(activity, 36))
            setBackgroundColor(PAGE_BACKGROUND)

            addView(TextView(activity).apply {
                text = "NeXtep"
                textSize = 30f
                setTextColor(TEXT_PRIMARY)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, matchWidth())
            addView(TextView(activity).apply {
                text = "定制顶部区域与常用 App"
                textSize = 14f
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(activity, 2), 0, dp(activity, 22))
            }, matchWidth())

            addView(MaterialButton(activity).apply {
                text = "添加 NeXtep 到控制中心"
                setOnClickListener {
                    isEnabled = false
                    runCatching {
                        activity.getSystemService(StatusBarManager::class.java)
                            .requestAddTileService(
                                ComponentName(activity, NeXtepTileService::class.java),
                                activity.getString(R.string.app_name),
                                Icon.createWithResource(activity, R.drawable.ic_nextep_tile),
                                activity.mainExecutor,
                            ) { result ->
                                isEnabled = true
                                val message = when (result) {
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加到控制中心"
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "控制中心已存在 NeXtep 入口"
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "未添加，可稍后重试"
                                    else -> "暂时无法添加，请在控制中心编辑页面重试"
                                }
                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                            }
                    }.onFailure {
                        isEnabled = true
                        Toast.makeText(activity, "请在控制中心编辑页面添加 NeXtep", Toast.LENGTH_LONG).show()
                    }
                }
            }, matchWidth().apply { bottomMargin = dp(activity, 14) })

            addView(sectionCard(activity).apply {
                addView(UpdateSection.create(activity))
            }, matchWidth().apply { bottomMargin = dp(activity, 14) })

            val titleInput = TextInputEditText(activity).apply {
                setText(settings.title)
                maxLines = 1
                textSize = 16f
            }
            addView(sectionCard(activity).apply {
                addView(sectionContent(activity).apply {
                    addView(sectionTitle(activity, "顶部名称"), matchWidth())
                    addView(sectionDescription(activity, "显示在布局切换与操作按钮之间。"), matchWidth())
                    addView(TextInputLayout(activity).apply {
                        hint = "顶部名称"
                        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                        val radius = dp(activity, 12).toFloat()
                        setBoxCornerRadii(radius, radius, radius, radius)
                        setBoxStrokeColor(ACCENT)
                        addView(titleInput, matchWidth())
                    }, matchWidth().apply { topMargin = dp(activity, 14) })
                    addView(MaterialButton(activity).apply {
                        text = "保存名称"
                        textSize = 14f
                        cornerRadius = dp(activity, 12)
                        setBackgroundColor(ACCENT)
                        setTextColor(Color.WHITE)
                        setOnClickListener {
                            repository.setTopTitle(titleInput.text?.toString().orEmpty())
                            titleInput.clearFocus()
                            activity.getSystemService(InputMethodManager::class.java)
                                ?.hideSoftInputFromWindow(titleInput.windowToken, 0)
                            Toast.makeText(activity, "名称已保存", Toast.LENGTH_SHORT).show()
                        }
                    }, matchWidth().apply { topMargin = dp(activity, 10) })
                })
            }, matchWidth())

            addView(View(activity), LinearLayout.LayoutParams(1, dp(activity, 14)))

            val selectedContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val availableContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val manualContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val automaticButton = modeButton(activity, "自动排序")
            val manualButton = modeButton(activity, "手动排序")
            val modeSelector = MaterialButtonToggleGroup(activity).apply {
                isSingleSelection = true
                isSelectionRequired = true
                addView(automaticButton, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
                addView(manualButton, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
                check(if (manualMode) manualButton.id else automaticButton.id)
            }
            val modeDescription = sectionDescription(activity, "")

            lateinit var render: () -> Unit
            render = {
                manualContainer.visibility = if (manualMode) View.VISIBLE else View.GONE
                modeDescription.text = if (manualMode) {
                    "开启 App，并长按已选项目调整顺序。"
                } else {
                    "根据最近使用情况自动排列。"
                }
                selectedContainer.removeAllViews()
                if (selected.isEmpty()) {
                    selectedContainer.addView(emptyHint(activity, "开启下方 App 开关后，会按顺序加入这里"))
                } else {
                    selected.forEachIndexed { index, name ->
                        val option = appByName[name] ?: return@forEachIndexed
                        selectedContainer.addView(
                            draggableSelectedRow(activity, option, name, index, selected, repository) {
                                render()
                            },
                        )
                    }
                }

                availableContainer.removeAllViews()
                apps.forEachIndexed { index, option ->
                    val componentName = option.component.flattenToString()
                    availableContainer.addView(appRow(activity, option, 42).apply {
                        addView(SwitchMaterial(activity).apply {
                            text = ""
                            isChecked = selected.contains(componentName)
                            contentDescription = if (isChecked) "移除 ${option.label}" else "添加 ${option.label}"
                            setOnCheckedChangeListener { _, enabled ->
                                if (enabled && selected.size >= MAX_TOP_APPS) {
                                    isChecked = false
                                    Toast.makeText(activity, "最多选择 $MAX_TOP_APPS 个 App", Toast.LENGTH_SHORT).show()
                                    return@setOnCheckedChangeListener
                                }
                                if (enabled) selected += componentName else selected.remove(componentName)
                                repository.setTopApps(selected)
                                render()
                            }
                        }, LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 48)))
                    })
                    if (index != apps.lastIndex) availableContainer.addView(divider(activity))
                }
            }

            addView(sectionCard(activity).apply {
                addView(sectionContent(activity).apply {
                    addView(sectionTitle(activity, "App 栏"), matchWidth())
                    addView(sectionDescription(activity, "自动模式按最近使用排序；手动模式可选择并拖动调整顺序。"), matchWidth())
                    addView(modeSelector, matchWidth().apply { topMargin = dp(activity, 14) })
                    addView(modeDescription, matchWidth().apply { topMargin = dp(activity, 8) })
                    addView(manualContainer.apply {
                        addView(subsectionTitle(activity, "已选 App"), matchWidth())
                        addView(selectedContainer, matchWidth())
                        addView(subsectionTitle(activity, "全部 App"), matchWidth().apply {
                            topMargin = dp(activity, 16)
                        })
                        addView(availableContainer, matchWidth())
                    }, matchWidth())
                })
            }, matchWidth())

            modeSelector.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                manualMode = checkedId == manualButton.id
                repository.setManualAppOrder(manualMode)
                render()
            }
            render()
        }
    }

    private fun draggableSelectedRow(
        activity: AppCompatActivity,
        option: AppOption,
        componentName: String,
        index: Int,
        selected: MutableList<String>,
        repository: SettingsRepository,
        render: () -> Unit,
    ): View = appRow(activity, option, 38).apply {
        contentDescription = "${option.label}，长按拖动排序"
        background = roundedBackground(SELECTED_BACKGROUND, dp(activity, 12).toFloat())
        setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 8), dp(activity, 5))
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.bottomMargin = dp(activity, 7)
        layoutParams = params
        isLongClickable = true
        setOnLongClickListener { row ->
            row.startDragAndDrop(
                ClipData.newPlainText("NeXtep App", componentName),
                View.DragShadowBuilder(row),
                componentName,
                0,
            )
        }
        setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                DragEvent.ACTION_DROP -> {
                    val dragged = event.localState as? String ?: return@setOnDragListener false
                    val from = selected.indexOf(dragged)
                    if (from >= 0 && from != index) {
                        selected.removeAt(from)
                        selected.add(index.coerceAtMost(selected.size), dragged)
                        repository.setTopApps(selected)
                        render()
                    }
                    true
                }
                else -> true
            }
        }
        addView(ImageView(activity).apply {
            setImageResource(io.github.lujinxin.nextep.R.drawable.ic_drag_handle)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "拖动调整 ${option.label} 的位置"
        }, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 48)))
    }

    private fun loadApps(activity: AppCompatActivity): List<AppOption> {
        val manager = activity.packageManager
        return manager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        ).asSequence()
            .filter { it.activityInfo?.exported == true && it.activityInfo?.enabled == true }
            .filterNot { it.activityInfo?.packageName == "com.android.stk" }
            .map { info ->
                val activityInfo = checkNotNull(info.activityInfo)
                AppOption(
                    component = ComponentName(activityInfo.packageName, activityInfo.name),
                    label = info.loadLabel(manager).toString(),
                    icon = info.loadIcon(manager),
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun appRow(activity: AppCompatActivity, option: AppOption, iconSizeDp: Int) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 60)
            addView(ImageView(activity).apply {
                setImageDrawable(option.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(activity, iconSizeDp), dp(activity, iconSizeDp)))
            addView(TextView(activity).apply {
                text = option.label
                textSize = 15f
                setTextColor(TEXT_PRIMARY)
                setPadding(dp(activity, 13), 0, dp(activity, 8), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun sectionCard(activity: AppCompatActivity) = MaterialCardView(activity).apply {
        radius = dp(activity, 16).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(Color.WHITE)
        strokeColor = CARD_OUTLINE
        strokeWidth = dp(activity, 1)
    }

    private fun modeButton(activity: AppCompatActivity, value: String) =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = value
            textSize = 14f
            cornerRadius = dp(activity, 12)
            insetTop = 0
            insetBottom = 0
            setTextColor(TEXT_PRIMARY)
        }

    private fun sectionContent(activity: AppCompatActivity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18))
    }

    private fun sectionTitle(activity: AppCompatActivity, value: String) = TextView(activity).apply {
        text = value
        textSize = 18f
        setTextColor(TEXT_PRIMARY)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun sectionDescription(activity: AppCompatActivity, value: String) = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(TEXT_SECONDARY)
        setPadding(0, dp(activity, 4), 0, 0)
    }

    private fun subsectionTitle(activity: AppCompatActivity, value: String) = TextView(activity).apply {
        text = value
        textSize = 14f
        setTextColor(TEXT_SECONDARY)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(activity, 14), 0, dp(activity, 9))
    }

    private fun emptyHint(activity: AppCompatActivity, value: String) = TextView(activity).apply {
        text = value
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(TEXT_TERTIARY)
        setPadding(dp(activity, 12), dp(activity, 18), dp(activity, 12), dp(activity, 18))
        background = roundedBackground(SELECTED_BACKGROUND, dp(activity, 12).toFloat())
    }

    private fun divider(activity: AppCompatActivity) = View(activity).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply { marginStart = dp(activity, 55) }
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private const val PAGE_BACKGROUND = 0xFFF5F7F9.toInt()
    private const val CARD_OUTLINE = 0xFFE1E7EB.toInt()
    private const val SELECTED_BACKGROUND = 0xFFEEF3F5.toInt()
    private const val DIVIDER = 0xFFE8EDF0.toInt()
    private const val TEXT_PRIMARY = 0xFF182126.toInt()
    private const val TEXT_SECONDARY = 0xFF52616A.toInt()
    private const val TEXT_TERTIARY = 0xFF77868E.toInt()
    private const val ACCENT = 0xFF285E72.toInt()
    private const val MAX_TOP_APPS = 36
}
