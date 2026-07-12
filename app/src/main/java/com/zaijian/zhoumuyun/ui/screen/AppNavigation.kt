package com.zaijian.zhoumuyun.ui.screen

import com.zaijian.zhoumuyun.ui.screen.chat.ChatScreen
import com.zaijian.zhoumuyun.ui.screen.characterdetail.CharacterDetailScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  v44 修复：底部导航栏高度共享值
// ─────────────────────────────────────────────────────────────
// 背景：之前 TaskCenterScreen/LearningGoalScreen/ProfileScreen 三个页面
// 各自独立读取 WindowInsets.navigationBars 再加 Spacing.bottomNavHeight，
// 三处重复计算同一个值——这种"各自算一遍"的写法本身就有风险：任何一处
// 手误、遗漏 insetBottom、或者未来 Spacing.bottomNavHeight 改动没有同步
// 到所有三个文件，都会导致该页面内容能滚动到导航栏物理区域后面（用户
// 要求：内容显示范围严禁超出导航栏上边缘，不允许"反正导航栏挡住看不见
// 就算了"）。改为唯一权威来源：在 AppNavigation.kt 的 Scaffold 里就近
// 算出真实高度，往下用 CompositionLocalProvider 传给整个 NavHost，所有
// 子页面一律从这里读，不再各自重算，从根源上消除多处口径不一致的可能。
val LocalBottomBarHeight = staticCompositionLocalOf { 0.dp }

/**
 * 单次 navigate 并附加 launchSingleTop，避免快速连点导致同一目标页被重复入栈。
 * 底部 Tab 切换因需要 popUpTo/restoreState 等附加选项，仍使用原生带 lambda 的 navigate。
 */
fun NavController.navigateSingle(route: String) {
    // Fix-13-15：navigate 传入无效路由时抛 IllegalArgumentException，捕获后记日志静默忽略。
    // 用户最差情况停留在当前页面，不会崩溃。
    try {
        navigate(route) {
            launchSingleTop = true
        }
    } catch (e: IllegalArgumentException) {
        android.util.Log.e("AppNavigation", "navigateSingle 路由无效，已忽略: route=$route", e)
    }
}

// ─────────────────────────────────────────────────────────────
//  Navigation routes
// ─────────────────────────────────────────────────────────────

sealed class AppRoute(val route: String) {
    object Splash          : AppRoute("splash")
    /** 离线简报：Splash 之后、World 之前的开场页，不进 bottomNavRoutes/
     *  detailRoutes——它既不是底部 Tab，也不是从 Tab 点进去的详情页，是
     *  独立的第二段过场，自己在 composable() 里声明 enterTransition/
     *  exitTransition，不依赖 NavHost 全局的 isDetailRoute() 判断
     *  （整合方案 v2.1 4.2 节）。 */
    object Briefing        : AppRoute("briefing")
    object World           : AppRoute("world")
    object Characters      : AppRoute("characters")
    object Tasks           : AppRoute("tasks")
    object Profile         : AppRoute("profile")
    object Chat            : AppRoute("chat/{characterId}") {
        fun createRoute(id: Int) = "chat/$id"
    }
    object CharacterDetail : AppRoute("character_detail/{characterId}") {
        fun createRoute(id: Int) = "character_detail/$id"
    }
    object ProjectList     : AppRoute("projects")
    object ProjectDetail   : AppRoute("project_detail/{projectId}") {
        fun createRoute(id: String) = "project_detail/$id"
    }
    /** 圆桌：characterIds 用逗号拼接，如 "1,2,3,4" */
    object Roundtable : AppRoute("roundtable/{memberIds}") {
        fun createRoute(ids: List<Int>) = "roundtable/${ids.joinToString(",")}"
    }
    /**
     * Phase 23：学习目标管理，传入初始角色 ID（默认 1）。
     * 2.3/2.4/3.4 核实结论：这个默认值 1 不是待修的硬编码 bug——
     * LearningGoalScreen 页面内自带 CharacterSelectorRow，用户进页后
     * 可自由切换角色，默认参数只决定 Tab 点击时展开哪一个，语义上
     * 相当于"打开应用默认显示第一项"，与需要选择器修复的场景不同。
     */
    object LearningGoals : AppRoute("learning_goals/{characterId}") {
        fun createRoute(characterId: Int = 1) = "learning_goals/$characterId"
    }
    /** P6 专长进化系统：专长档案页 */
    object SpecialtyEvolution : AppRoute("specialty_evolution/{characterId}") {
        fun createRoute(characterId: Int) = "specialty_evolution/$characterId"
    }
    /** 待办11：我们的故事时间线。characterId 为空表示跨角色总览，
     *  用查询参数而非路径段承载，未传值时是真正的 null，不借助任何
     *  数字哨兵值（如 -1）表示"无"。 */
    object Timeline : AppRoute("timeline?characterId={characterId}") {
        fun createRoute(characterId: Int? = null) =
            if (characterId != null) "timeline?characterId=$characterId" else "timeline"
    }
    /** 书架家族页：点击书架格子进入，展示母亲+全部后代 */
    object FamilyPage : AppRoute("family/{motherId}") {
        fun createRoute(motherId: Int) = "family/$motherId"
    }
    /** Stage A+B：全局日程视图 */
    object GlobalSchedule : AppRoute("global_schedule")
    /** 1.8：文件库（FileVault）——角色专属文件管理页 */
    object FileVault : AppRoute("file_vault/{characterId}") {
        fun createRoute(characterId: Int) = "file_vault/$characterId"
    }
    /** 窗口6：裁判与竞争机制——竞赛轮次页，按专长方向（domain）进入 */
    object Competition : AppRoute("competition/{domain}") {
        fun createRoute(domain: String) = "competition/${android.net.Uri.encode(domain)}"
    }
    /** 窗口6：裁判与竞争机制——裁判标准训练页，按角色 ID 进入 */
    object JudgeProfile : AppRoute("judge_profile/{characterId}") {
        fun createRoute(characterId: Int) = "judge_profile/$characterId"
    }
    /** U2 修复：角色个人日程独立页，供通知/深链接直达，按角色 ID 进入 */
    object PersonalSchedule : AppRoute("personal_schedule/{characterId}") {
        fun createRoute(characterId: Int) = "personal_schedule/$characterId"
    }
}

// Bottom nav tabs (root destinations only)
private val bottomNavRoutes = listOf(
    AppRoute.World.route,
    AppRoute.Characters.route,
    AppRoute.Tasks.route,
    AppRoute.LearningGoals.route, // Phase 27：学习目标底部快捷入口
    AppRoute.Profile.route,
)

// Detail pages (slide-in/out transitions)
private val detailRoutes = listOf(
    "chat/",
    "character_detail/",
    "projects",
    "project_detail/",
    "roundtable/",
    "family/",
    "file_vault/",
    "timeline",
    "specialty_evolution/",
    "competition/",
    "judge_profile/",
    "personal_schedule/",
    "global_schedule",
    // 注意：learning_goals/ 已在 Phase 27 升级为底部 Tab，
    // 不再作为详情页处理，Tab 切换应使用 crossfade 而非 slideIn。
)

private data class BottomNavItem(
    val route: String,              // 用于选中状态检测（路由模板）
    val icon: ImageVector,
    val label: String,
    val targetRoute: String = route, // 实际导航目标（可携带参数，默认同 route）
    val badge: Int? = null,
)

private val bottomNavItems = listOf(
    BottomNavItem(AppRoute.World.route,         Icons.Outlined.Home,        "公馆"),
    BottomNavItem(AppRoute.Characters.route,    Icons.Outlined.MenuBook,    "书架"),
    BottomNavItem(AppRoute.Tasks.route,         Icons.Outlined.CheckCircle, "任务"),
    // P3-C：学习目标 Tab 改名为「成长」，图标改为 TrendingUp
    BottomNavItem(
        route       = AppRoute.LearningGoals.route,
        icon        = Icons.Outlined.TrendingUp,
        label       = "成长",
        // 2.3/2.4/3.4 核实结论：这里的 (1) 不是 characterId 硬编码 bug——
        // LearningGoalScreen 页面内部自带 CharacterSelectorRow（横向头像条），
        // 进页后可自由切换角色，此处只是"点击 Tab 默认展开哪个角色"的初始值，
        // 与 TaskCenterScreen「目标」按钮那种点了直接锁死跳转、无法在目标页
        // 换人的硬编码性质不同，不适用同一套弹窗选择器修复方案。
        targetRoute = AppRoute.LearningGoals.createRoute(1),
        badge = null,
    ),
    BottomNavItem(AppRoute.Profile.route,       Icons.Outlined.Person,      "我"),
)

// ─────────────────────────────────────────────────────────────
//  Transition helpers  （设计规范 §18）
//
//  Tab 切换   → crossfade 150ms
//  进入详情页  → slideInRight + fadeIn 250ms
//  返回        → slideOutRight + fadeOut 200ms
// ─────────────────────────────────────────────────────────────

/** 判断目标路由是否是详情页（需要 slide 动画） */
private fun String?.isDetailRoute() =
    detailRoutes.any { prefix -> this?.startsWith(prefix) == true }

/**
 * 将 currentRoute（已解析，如 "learning_goals/1"）与路由模板（如
 * "learning_goals/{characterId}"）做匹配。
 * - 无参路由：直接 == 比较。
 * - 有参路由：取 '{' 前的前缀做 startsWith，避免模板 vs 真实值不等问题。
 */
private fun String?.matchesRouteTemplate(template: String): Boolean {
    if (this == null) return false
    val prefix = template.substringBefore('{')
    return this == template || (prefix.isNotEmpty() && this.startsWith(prefix))
}

// ─── Enter specs ────────────────────────────────────────────

/** Tab 切换：crossfade（仅 fade，无 scale） */
private val tabEnter = fadeIn(tween(AnimDuration.fast))

/** 进入详情页：从右侧滑入 + 淡入 */
private val detailEnter =
    slideInHorizontally(tween(AnimDuration.pageSwitch)) { it / 5 } +
    fadeIn(tween(AnimDuration.pageSwitch))

// ─── Exit specs ─────────────────────────────────────────────

/** Tab 切换：crossfade */
private val tabExit = fadeOut(tween(AnimDuration.fast))

/** 从 Tab 跳到详情页时，Tab 页轻微淡出（不滑出，避免晕眩） */
private val tabToDetailExit = fadeOut(tween(AnimDuration.fast))

/** 返回：向右滑出 + 淡出 */
private val detailPopExit =
    slideOutHorizontally(tween(AnimDuration.pageSwitch - 50)) { it / 5 } +
    fadeOut(tween(AnimDuration.pageSwitch - 50))


// ─────────────────────────────────────────────────────────────
//  App scaffold with bottom navigation
// ─────────────────────────────────────────────────────────────

@Composable
fun AppNavigation(
    pendingRoute: String? = null,
    onPendingRouteConsumed: () -> Unit = {},
    pendingJobId: String? = null,
    onPendingJobIdConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val colors        = ZaijianTheme.colors
    val type          = ZaijianTheme.typography
    val navBackStack  by navController.currentBackStackEntryAsState()
    val currentRoute  = navBackStack?.destination?.route

    // Fix-11: bgStyleIndex 从 DataStore flow 读取（响应式，ProfileScreen 写入后自动更新）
    // 通知点击深链：pendingRoute 非空时导航到对应路由
    val currentPendingRoute = pendingRoute
    LaunchedEffect(currentPendingRoute) {
        if (currentPendingRoute != null) {
            // P1-13-15 修复：深链路由字符串可能不合法（拼接错误的 jobId/characterId、
            // 路由模板不存在等），navigate() 抛出 IllegalArgumentException 会导致整个
            // Compose 树崩溃。包一层 try-catch，导航失败时静默忽略（用户最差情况停留在
            // 当前页面，而不是整个 App 崩溃退出）。
            try {
                navController.navigate(currentPendingRoute) {
                    popUpTo(navController.graph.startDestinationId) { saveState = false }
                    launchSingleTop = true
                }
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("AppNavigation", "深链导航失败，路由不存在: $currentPendingRoute", e)
            }
            onPendingRouteConsumed()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val appearanceStore = remember { com.zaijian.zhoumuyun.data.datastore.AppearanceDataStore(context.applicationContext) }
    val bgStyleIndex by appearanceStore.bgStyleIndexFlow.collectAsStateWithLifecycle(initialValue = 0)

    // UI M4 修复（完整版）：将 DAO 调用移入 BottomNavBadgeViewModel，
    // Composable 只做 collectAsState，彻底消除 UI 层直连数据层的问题。
    val badgeViewModel: com.zaijian.zhoumuyun.ui.viewmodel.BottomNavBadgeViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    val incompleteGoalCount by badgeViewModel.incompleteGoalCount.collectAsStateWithLifecycle(initialValue = 0)


    // 全屏页面（聊天、详情）隐藏底部导航
    val showBottomBar = bottomNavRoutes.any { currentRoute.matchesRouteTemplate(it) }

    // v44 修复：真实导航栏高度只在这里算一次，往下唯一传递（见 LocalBottomBarHeight
    // 定义处的详细说明）。非 Tab 页（showBottomBar=false）没有导航栏遮挡，值为 0。
    val navBarInsetBottomForLocal = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightForLocal = if (showBottomBar) Spacing.bottomNavHeight + navBarInsetBottomForLocal else 0.dp

    Scaffold(
        containerColor = colors.bgBase,
        // Fix-Insets-Global：外层 Scaffold 清零 WindowInsets，
        // 防止顶部状态栏/底部手势条高度被塞入 innerPadding 后，
        // 再由各子页面自己的 statusBarsPadding()/navigationBarsPadding() 二次叠加。
        // 各子页面自行负责正确的 insets 消费。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                // 布局修复 v2：上一版 `.navigationBarsPadding().height(bottomNavHeight)`
                // 是两个独立 modifier 顺序叠加——navigationBarsPadding() 先在内容
                // 外面加了一圈"手势条高度"的 padding（含底部），height() 再对
                // 加完 padding 之后剩下的内容区强制设成 64dp。结果整条 NavigationBar
                // 实际总高度 = 64dp + 手势条高度，而且手势条那部分 padding 是加在
                // "内容区下方"，即 64dp 内容区的下边缘并不贴屏幕最底部，两者之间
                // 空出了一条系统手势条高度的缝隙——背景内容会从这条缝隙露出来，
                // 正是用户截图里看到的"底栏没有对齐最下沿，下面漏出一截"。
                // 正确做法：不能把手势条 inset 处理成"额外的外部空间"，而要处理成
                // "总高度内部、贴底部的安全区"——用 Box 包一层，总高度 =
                // bottomNavHeight + 手势条高度，NavigationBar 本身仍是 bottomNavHeight，
                // 靠 Box 的 Alignment.TopStart 把它钉在顶部，手势条空间自然留在它
                // 下方且属于同一个容器，容器底边才是真正贴合屏幕最底部的那条线。
                val navBarColor = if (colors.isDark)
                    colors.bgCard.copy(alpha = 0.88f)
                else
                    colors.bgBase.copy(alpha = 0.88f)

                // 手势条高度作为额外的底部安全区"加"在总高度上（而不是
                // 挤占 64dp 内容区，也不是加在 64dp 外面制造缝隙）：
                // 整个 Box 高度 = 64dp + insetBottom，背景色铺满整个 Box
                // （包括手势条那一小截），NavigationBar 本身固定 64dp，
                // 用 Alignment.TopStart 钉在 Box 顶部——这样 Box 的下边缘
                // 精确贴合屏幕最底部，手势条区域和导航栏是同一块背景色，
                // 视觉上浑然一体，不会露出缝隙。
                val insetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.bottomNavHeight + insetBottom)
                        .background(navBarColor),
                ) {
                // v19 修复：不再用 Material3 NavigationBar/NavigationBarItem。
                // 这两个组件内部自带远超 Spacing.bottomNavHeight 声明值的最小
                // 触控高度和内边距（图标+文字+选中指示器叠加），导致即使外面
                // 用 .height(bottomNavHeight) 强制约束，实际视觉高度依然超出，
                // 就是用户反馈"底栏太大，把公馆正门都挡住了"的根因——这不是
                // 数值设小了，是组件本身不服从外部给的高度。改用一个纯手写的
                // Row，每一项就是一个可点击的 Column（图标 + 文字），不引入
                // 任何 Material3 默认内边距，实际渲染高度严格等于
                // Spacing.bottomNavHeight，所见即所得。
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(Spacing.bottomNavHeight),
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute.matchesRouteTemplate(item.route)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement  = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication        = null,
                                ) {
                                    navController.navigate(item.targetRoute) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState    = true
                                    }
                                }
                                .padding(horizontal = 4.dp),
                        ) {
                            val iconTint = if (selected) colors.accent else colors.textSecondary
                            Box(contentAlignment = Alignment.Center) {
                                if ((item.label == "成长") && incompleteGoalCount > 0) {
                                    val count = incompleteGoalCount
                                    BadgedBox(
                                        badge = {
                                            Badge { Text(if (count > 99) "99+" else count.toString()) }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.label,
                                            tint = iconTint,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.label,
                                        tint = iconTint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Text(
                                text  = item.label,
                                style = type.label,
                                color = iconTint,
                            )
                            // 选中指示点：4dp 圆点 fade+scale 动画，紧贴文字下方
                            androidx.compose.animation.AnimatedVisibility(
                                visible  = selected,
                                enter    = fadeIn(tween(AnimDuration.fast)) +
                                           scaleIn(tween(AnimDuration.fast), 0.5f),
                                exit     = fadeOut(tween(AnimDuration.fast)) +
                                           scaleOut(tween(AnimDuration.fast), 0.5f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .size(3.dp)
                                        .background(colors.accent, CircleShape),
                                )
                            }
                        }
                    }
                }
                }
            }
        },
    ) { innerPadding ->
        // innerPadding 仅包含 bottomBar 高度（contentWindowInsets=0，顶部为 0）。
        // 不把它 padding 到 NavHost 上：底部 Tab 页（World/Characters）是全屏背景图，
        // 底部 Tab 浮在上面，页面无需留白；详情页自己处理底部安全区。
        // 如需让某页内容不被底栏遮挡，该页自行加 navigationBarsPadding() 或固定 padding。
        //
        // v44 修复：用 CompositionLocalProvider 把刚算好的 bottomBarHeightForLocal
        // 往下传给整个 NavHost，子页面统一用 LocalBottomBarHeight.current 读取，
        // 不再各自重新计算 WindowInsets.navigationBars + Spacing.bottomNavHeight。
        CompositionLocalProvider(LocalBottomBarHeight provides bottomBarHeightForLocal) {
        NavHost(
            navController    = navController,
            startDestination = AppRoute.Splash.route,
            modifier         = Modifier.fillMaxSize(),

            // ── 默认进入动画：Tab crossfade ──────────────────
            enterTransition  = {
                val target = targetState.destination.route
                if (target.isDetailRoute()) detailEnter else tabEnter
            },

            // ── 默认退出动画 ──────────────────────────────────
            exitTransition   = {
                val target = targetState.destination.route
                if (target.isDetailRoute()) tabToDetailExit else tabExit
            },

            // ── popBackStack 时的进入（详情页弹出后 Tab 重新出现）
            popEnterTransition = {
                fadeIn(tween(AnimDuration.fast))
            },

            // ── popBackStack 时的退出（详情页向右滑出）
            popExitTransition = {
                val initial = initialState.destination.route
                if (initial.isDetailRoute()) detailPopExit else tabExit
            },
        ) {
            // ── Splash ─────────────────────────────────────────
            composable(
                route           = AppRoute.Splash.route,
                enterTransition = { fadeIn(tween(AnimDuration.fast)) },
                exitTransition  = { fadeOut(tween(AnimDuration.pageSwitch)) },
            ) {
                SplashScreen(
                    onFinished = {
                        navController.navigate(AppRoute.Briefing.route) {
                            popUpTo(AppRoute.Splash.route) { inclusive = true }
                        }
                    },
                )
            }

            // ── Briefing（新增：离线简报开场页，整合方案 v2.1 4.2 节）──
            composable(
                route           = AppRoute.Briefing.route,
                enterTransition = { fadeIn(tween(AnimDuration.pageSwitch)) },
                exitTransition  = { fadeOut(tween(AnimDuration.pageSwitch)) },
            ) {
                BriefingScreen(
                    onEnterWorld = {
                        navController.navigate(AppRoute.World.route) {
                            popUpTo(AppRoute.Briefing.route) { inclusive = true }
                        }
                    },
                )
            }

            // ── Root tabs ──────────────────────────────────────

            composable(AppRoute.World.route) {
                WorldScreen(
                    onNavigateToChat       = { id -> navController.navigateSingle(AppRoute.Chat.createRoute(id)) },
                    onNavigateToProfile    = { id -> navController.navigateSingle(AppRoute.CharacterDetail.createRoute(id)) },
                    onNavigateToTasks      = { navController.navigateSingle(AppRoute.Tasks.route) },
                    onNavigateToRoundtable = { ids -> navController.navigateSingle(AppRoute.Roundtable.createRoute(ids)) },
                    bgStyleIndex           = bgStyleIndex,
                )
            }

            composable(AppRoute.Characters.route) {
                CharacterScreen(
                    onNavigateToDetail = { id ->
                        navController.navigateSingle(AppRoute.CharacterDetail.createRoute(id))
                    },
                    onNavigateToFamily = { id ->
                        navController.navigateSingle(AppRoute.FamilyPage.createRoute(id))
                    },
                    onNavigateToChat   = { id ->
                        navController.navigateSingle(AppRoute.Chat.createRoute(id))
                    },
                    // Phase 30 方案二：任务完成浮层「立即查看」跳转
                    onNavigateToTaskCenter = {
                        navController.navigateSingle(AppRoute.Tasks.route)
                    },
                    bgStyleIndex = bgStyleIndex,
                )
            }

            composable(AppRoute.Tasks.route) {
                TaskCenterScreen(
                    onNavigateToProjects = { navController.navigateSingle(AppRoute.ProjectList.route) },
                    onNavigateToSchedule = { navController.navigateSingle(AppRoute.GlobalSchedule.route) },
                    pendingJobId         = pendingJobId,
                    onPendingJobIdConsumed = onPendingJobIdConsumed,
                )
            }
            composable(AppRoute.Profile.route) {
                ProfileScreen(
                    onNavigateToCharacter = { id ->
                        navController.navigateSingle(AppRoute.CharacterDetail.createRoute(id))
                    },
                )
            }

            // ── Project pages ──────────────────────────────────

            composable(AppRoute.ProjectList.route) {
                ProjectScreen(
                    onNavigateToDetail = { id ->
                        navController.navigateSingle(AppRoute.ProjectDetail.createRoute(id))
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route     = AppRoute.ProjectDetail.route,
                arguments = listOf(navArgument("projectId") { this.type = NavType.StringType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("projectId") ?: return@composable
                ProjectDetailScreen(
                    projectId = id,
                    onBack    = { navController.popBackStack() },
                )
            }

            // ── Detail pages ───────────────────────────────────

            composable(
                route     = AppRoute.Chat.route,
                arguments = listOf(navArgument("characterId") { this.type = NavType.IntType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getInt("characterId") ?: return@composable
                ChatScreen(
                    characterId         = id,
                    onBack              = { navController.popBackStack() },
                    onNavigateToProfile = { charId ->
                        navController.navigateSingle(AppRoute.CharacterDetail.createRoute(charId))
                    },
                )
            }

            composable(
                route     = AppRoute.CharacterDetail.route,
                arguments = listOf(navArgument("characterId") { this.type = NavType.IntType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getInt("characterId") ?: return@composable
                CharacterDetailScreen(
                    characterId     = id,
                    onBack          = { navController.popBackStack() },
                    onStartChat     = { charId ->
                        navController.navigateSingle(AppRoute.Chat.createRoute(charId))
                    },
                    // Phase 27：从角色详情「目标」Tab 直接跳转到 LearningGoalScreen
                    onNavigateToGoals = { charId ->
                        navController.navigateSingle(AppRoute.LearningGoals.createRoute(charId))
                    },
                    // P6 专长进化系统：从角色详情「目标」Tab 直接跳转到专长档案页
                    onNavigateToSpecialty = { charId ->
                        navController.navigateSingle(AppRoute.SpecialtyEvolution.createRoute(charId))
                    },
                    onNavigateToTimeline = { charId ->
                        navController.navigateSingle(AppRoute.Timeline.createRoute(charId))
                    },
                    // 1.8：文件库入口
                    onNavigateToFileVault = { charId ->
                        navController.navigateSingle(AppRoute.FileVault.createRoute(charId))
                    },
                    // U1 修复：角色详情 → 专长页 → 竞赛页完整链路
                    onNavigateToCompetition = { domain ->
                        navController.navigateSingle(AppRoute.Competition.createRoute(domain))
                    },
                    // 精修方案 v1.3 第5.1节：「关联项目」WrapChipGroup 点击跳转项目详情页
                    onNavigateToProjectDetail = { projectId ->
                        navController.navigateSingle(AppRoute.ProjectDetail.createRoute(projectId))
                    },
                    // P3-44 修复：从孕育记录点击跳转到子代角色详情页
                    onNavigateToCharacterDetail = { charId ->
                        navController.navigateSingle(AppRoute.CharacterDetail.createRoute(charId))
                    },
                )
            }

            // ── Roundtable ─────────────────────────────────────
            composable(
                route     = AppRoute.Roundtable.route,
                arguments = listOf(navArgument("memberIds") { this.type = NavType.StringType }),
            ) { backStackEntry ->
                val raw = backStackEntry.arguments?.getString("memberIds") ?: return@composable
                val ids = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (ids.isEmpty()) return@composable  // 解析结果为空（参数格式错误），不进入圆桌
                RoundtableScreen(
                    characterIds = ids,
                    onBack       = { navController.popBackStack() },
                )
            }

            // ── Learning Goals（Phase 23）──────────────────────
            composable(
                route     = AppRoute.LearningGoals.route,
                arguments = listOf(navArgument("characterId") { this.type = NavType.IntType }),
            ) { backStackEntry ->
                // 2.3/2.4/3.4 核实结论：这里的 `?: 1` 和下面 `!= 1` 同样不是硬编码 bug——
                // 角色 1 是本 Tab 的默认展开项（页面内可经 CharacterSelectorRow 自由换人），
                // `showBackButton = (characterId != 1)` 的语义是"非默认入口（如从角色详情页
                // 「目标」Tab 深链跳转进来）才显示返回按钮"，两者都依赖同一个"1 = 默认值"
                // 的约定，一并保留。详见上方 bottomNavItems 中 targetRoute 的说明。
                val characterId = backStackEntry.arguments?.getInt("characterId") ?: 1
                LearningGoalScreen(
                    initialCharacterId   = characterId,
                    showBackButton       = (characterId != 1),
                    onBack               = { navController.popBackStack() },
                    onNavigateToChat     = { id ->
                        navController.navigateSingle(AppRoute.Chat.createRoute(id))
                    },
                    onNavigateToProject  = { projectId ->
                        navController.navigateSingle(AppRoute.ProjectDetail.createRoute(projectId))
                    },
                )
            }

            // ── Timeline 我们的故事（待办11）────────────────────
            // characterId 用可空 StringType 查询参数承载：不传时 Navigation
            // 组件给出的就是真正的 null，不需要 -1 之类的哨兵值来判断"缺省"。
            composable(
                route = AppRoute.Timeline.route,
                arguments = listOf(
                    navArgument("characterId") {
                        this.type = NavType.StringType
                        this.nullable = true
                        this.defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val characterId = backStackEntry.arguments?.getString("characterId")?.toIntOrNull()
                TimelineScreen(
                    characterId = characterId,
                    onBack      = { navController.popBackStack() },
                )
            }

            // ── 书架家族页（FamilyScreen）───────────────────────
            composable(
                route     = AppRoute.FamilyPage.route,
                arguments = listOf(navArgument("motherId") { this.type = NavType.IntType }),
            ) { backStackEntry ->
                val motherId = backStackEntry.arguments?.getInt("motherId") ?: return@composable
                FamilyScreen(
                    motherId           = motherId,
                    onBack             = { navController.popBackStack() },
                    onNavigateToDetail = { charId ->
                        navController.navigateSingle(AppRoute.CharacterDetail.createRoute(charId))
                    },
                )
            }

            // ── 文件库（FileVaultScreen）1.8 修复：重新接入路由 ──
            composable(
                route     = AppRoute.FileVault.route,
                arguments = listOf(navArgument("characterId") { this.type = NavType.IntType }),
            ) { backStackEntry ->
                val charId = backStackEntry.arguments?.getInt("characterId") ?: return@composable
                FileVaultScreen(
                    characterId = charId,
                    onBack      = { navController.popBackStack() },
                )
            }

            // ── SpecialtyEvolution（P6 专长进化系统）──────────
            composable(
                route     = AppRoute.SpecialtyEvolution.route,
                arguments = listOf(navArgument("characterId") { this.type = NavType.IntType }),
            ) { backStackEntry ->
                val characterId = backStackEntry.arguments?.getInt("characterId") ?: return@composable
                SpecialtyEvolutionScreen(
                    characterId = characterId,
                    onBack      = { navController.popBackStack() },
                    onNavigateToCompetition = { domain ->
                        navController.navigateSingle(AppRoute.Competition.createRoute(domain))
                    },
                )
            }

            // ── Competition（窗口6：裁判与竞争机制——竞赛轮次页）──
            composable(
                route     = AppRoute.Competition.route,
                arguments = listOf(navArgument("domain") { this.type = NavType.StringType }),
            ) { backStackEntry ->
                val domain = android.net.Uri.decode(
                    backStackEntry.arguments?.getString("domain") ?: ""
                )
                CompetitionScreen(
                    domain = domain,
                    onBack = { navController.popBackStack() },
                    onNavigateToJudgeProfile = { charId ->
                        navController.navigateSingle(AppRoute.JudgeProfile.createRoute(charId))
                    },
                )
            }

            // ── JudgeProfile（窗口6：裁判与竞争机制——裁判标准训练页）─
            composable(
                route     = AppRoute.JudgeProfile.route,
                arguments = listOf(navArgument("characterId") { this.type = NavType.IntType }),
            ) { backStackEntry ->
                val charId = backStackEntry.arguments?.getInt("characterId") ?: return@composable
                JudgeProfileScreen(
                    characterId = charId,
                    onBack      = { navController.popBackStack() },
                    onNavigateToCompetition = { domain ->
                        navController.navigateSingle(AppRoute.Competition.createRoute(domain))
                    },
                )
            }

            // ── GlobalSchedule（Stage A+B：全局日程视图）──────
            composable(route = AppRoute.GlobalSchedule.route) {
                GlobalScheduleScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            // ── PersonalSchedule（U2 修复：角色个人日程独立页，────
            //    供通知/深链接直达，不依赖先进入 CharacterDetail 再切 Tab）
            composable(
                route     = AppRoute.PersonalSchedule.route,
                arguments = listOf(navArgument("characterId") { this.type = NavType.IntType }),
            ) { backStackEntry ->
                val charId = backStackEntry.arguments?.getInt("characterId") ?: return@composable
                PersonalScheduleScreen(
                    characterId = charId,
                    onBack      = { navController.popBackStack() },
                )
            }
        }
        }
    }

}
