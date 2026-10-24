# TV Player 界面规范

所有样式定义在 `app/src/main/res/values/themes.xml`，颜色在同一文件顶部和 `res/color/`。新页面或新组件请直接引用下面的样式名，不要在布局里另写颜色和字号。

## 原则

- **海报是主角，界面要安静。** 标题、说明、状态一律用白色或灰色。
- **蓝色只有两种含义：选中，或者这一页的主要操作。** 每张卡片最多一个蓝色实心按钮，标题不用蓝色。
- **同一种组件只有一种样子。** 标签、按钮、分段选项在每个页面都一样。
- **选中靠底色区分，不加对勾，不加描边。** 遥控器焦点统一用 2dp 蓝色描边。

## 颜色

| 名称 | 值 | 用途 |
|---|---|---|
| `bg` | #0E1015 | 页面背景 |
| `surface` | #161923 | 卡片、底部导航 |
| `surface_high` | #1F2330 | 标签、分段选项、步进器、次要按钮、输入框 |
| `outline` | #363C50 | 分段选项的分隔线、描边 |
| `on_surface` | #E8EAF0 | 主要文字、图标 |
| `on_surface_variant` | #A4ABBD | 说明、数量、状态等次要文字 |
| `accent` | #6EA8FF | 主要按钮、焦点描边、文字链接、"新"角标 |
| `accent_container` | #1E3B6B | 选中状态的底色（文字用 #D6E4FF） |

## 字号

| 样式 | 大小 | 用在 |
|---|---|---|
| `TextAppearance.TvPlayer.Headline` | 22sp 粗 | 页面标题（发现、直播、我的、设置）、电视播放页片名 |
| `TextAppearance.TvPlayer.Section` | 16sp 粗 | 卡片和小节标题（线路、选集、我的源…） |
| `TextAppearance.TvPlayer.ItemTitle` | 16sp | 列表项标题、空状态标题 |
| `TextAppearance.TvPlayer.Body` | 14sp | 正文、播放状态 |
| `TextAppearance.TvPlayer.Caption` | 12sp 灰 | 说明文字、数量、提示 |
| 海报卡片 | 片名 14sp / 副标题 12sp 灰 / 角标 11sp | `item_vod.xml` |

## 间距和圆角

- 间距用 4dp 的倍数：页面左右 12–16dp，卡片内边距 16dp，小节之间 16–20dp，同组元素之间 8dp。
- 圆角按层级：海报 10dp，卡片 16dp，标签和选项 10dp，搜索框、按钮、分段选项和步进器用胶囊形。
- 可点区域不小于 48 × 48dp（Android 无障碍规范）。标签看起来是 34dp 高，靠 `chipMinTouchTargetSize` 补足到 48dp；分段选项、步进器、选集格子直接做成 48dp。

## 组件

| 组件 | 样式 / 文件 | 规则 |
|---|---|---|
| 标签（历史、筛选、设置选项、直播分组、我的） | `Widget.TvPlayer.Chip`（主题里的 `chipStyle`，代码里 `Chip(context)` 也自动用它） | 深灰底；选中换 `accent_container` 底，不显示对勾 |
| 线路 / 选集格子 | `item_chip.xml` + `drawable/bg_chip.xml` | 与标签同色；选集在网格里等宽（`ChipAdapter(fill = true)`） |
| 分段选项（倍速、睡眠定时、切出方式） | `Widget.TvPlayer.Segment`，单个按钮 `layout/toggle_option.xml` | 一行等宽，选项不超过 5 个，文字不超过 4 个字 |
| 数值调节（跳过片头 / 片尾） | `Widget.TvPlayer.StepButton` + `drawable/bg_stepper.xml` | 「− 数值 +」，单击一步，按住连续调；不用滑动条 |
| 电视点播详情页 | `activity_play_tvdetail.xml` | 参照小米 / 华为电视视频 App：海报模糊铺满背景；左上小窗播放，右侧片名、评分 / 年份 / 类型 / 地区 / 更新状态、导演主演、三行简介（确认键看全文）、全屏播放 / 收藏 / 设置；下方线路和选集。选集超过 30 集按「1-30」「31-60」分段，格子只显示集号。确认键点小窗或「全屏播放」进入全屏，返回键回到详情页；直播直接全屏 |
| 播放设置面板 | 手机、平板用底部面板（`BottomSheetDialog` + 拖动条）；电视用右侧整高侧栏（`Theme.TvPlayer.SidePanel`，420dp 宽，从右滑入） | 底部面板方便单手操作；电视侧栏不挡左边画面，返回键关闭 |
| 主要按钮 | `MaterialButton` 默认样式（蓝色实心） | 每张卡片最多一个 |
| 次要按钮 | `Widget.TvPlayer.TonalButton`（`surface_high` 底，遥控器焦点时 2dp 蓝色描边） | 其余所有按钮 |
| 播放页操作（收藏、投屏、设置） | `Widget.TvPlayer.Action` | 手机：图标在上、文字在下，不用色块。电视：画面全屏，操作放在底部控制栏（`activity_play_overlay.xml`），按确认键或下键弹出，播放时 6 秒无操作自动隐藏，返回键先收起控制栏；电视上不显示投屏 |
| 卡片 | `Widget.TvPlayer.Card` | `surface` 底，16dp 圆角，无阴影 |
| 海报角标 | `bg_badge_muted`（来源数）、`bg_badge`（"新"） | 只有需要注意的信息才用蓝色 |
| 遥控器焦点（海报、方块卡片） | `drawable/bg_focus.xml`、`zoomOnFocus()` | 2–3dp 蓝色描边并放大（电视 1.1 倍）。放大会超出自己的格子，所以所在的滚动区域要留出余量（内边距，纵向滚动再加 `fadingEdgeLength`），否则边缘被裁 |
| 遥控器焦点（整行宽度的列表项，如直播频道） | `drawable/bg_focus_row.xml` | 不放大：一行放大一定会被列表左右边界裁掉。用行内圆角描边加淡底色，全部画在行里面 |
| 侧边导航（电视、平板） | `bg_nav_panel.xml`、`nav_header.xml`、`Widget.TvPlayer.NavRail` | 导航放在自己的圆角面板上（`surface` 底），顶部是应用标记；选中项用浅蓝字加 `accent_container` 指示条，其余灰色 |
| 切换动画 | `res/animator/fade_*.xml`、`res/anim/page_*.xml`、`Animation.TvPlayer.Page` | 只用透明度和 0.98→1 的轻微缩放，100–180 毫秒，不做位移或模糊动画，弱盒子上也不卡；系统的「动画缩放」关闭时自动失效 |
| 电视首页 | `view_tv_hero.xml`、`SearchFragment` | 顶部固定一块横幅（不放进滚动区），焦点移到哪部片它就显示哪部：模糊海报做背景，右侧放清晰海报；下面是横向行（继续观看、各榜单）。横幅更新延迟 150 毫秒，避免快速滑动时闪烁 |
| 输入框 | `Widget.Material3.TextInputLayout.OutlinedBox` | |
| 弹窗 | `MaterialAlertDialogBuilder` | 标题说清楚要做什么，确认按钮写动作本身（"清空""移除"），不用"确定" |

## Android 平台规则

依据 Android 官方的无障碍和 TV 设计指南：

- **点击区域**：至少 48 × 48dp。
- **文字对比度**：小于 18sp 的文字至少 4.5:1，其余至少 3:1。现在的灰色说明文字 `on_surface_variant` 在背景上约 8:1，选中文字 #D6E4FF 在 `accent_container` 上约 8:1。
- **电视安全区**：电视可能裁掉画面边缘（过扫描）。按 960 × 540dp 的设计尺寸，内容离左右边至少 48dp、离上下边约 27dp；背景和视频画面不受限制，可以铺满。代码里只在电视模式下加这层边距（`MainActivity`、`PlayActivity` 侧栏）。
- **电视操作**：每个方向键都要有明确用途，不能有只能触摸才能用的功能。焦点要清楚可见。
- **返回手势**：开启了预测性返回（`enableOnBackInvokedCallback`），返回逻辑统一用 `onBackPressedDispatcher`，不要覆写 `onBackPressed()`。
- **主题图标**：启动图标提供了单色图层，Android 13 以上跟随系统主题色。

- **播放时不锁屏**：正在播放时保持屏幕常亮，暂停后恢复系统的锁屏和屏保时间。

## 文案

- 说用户能看懂的话：写"线路""换源"，不写"接口""解析"。
- 按钮写动作，结果提示沿用同一个词：点"清空"，提示"已清空"。
- 出错时说清楚发生了什么、可以怎么办，比如"热播榜没加载出来，点这里重试，或直接搜索"。
