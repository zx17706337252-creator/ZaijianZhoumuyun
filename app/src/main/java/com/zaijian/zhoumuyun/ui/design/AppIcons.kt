package com.zaijian.zhoumuyun.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  AppIcons — 图标统一收口（精修方案 v2.1 2.4）
//
//  总原则：不重新画一套图标库，用"色板+等宽数字+色块背景"包装 Material
//  默认图标提升识别度，不建分模块的图标文件体系——这是唯一的一个
//  AppIcons.kt，不按模块拆分成子文件。
//
//  命名约定：按"图标身份"命名（不按使用场景），与 Folder/CalendarMonth 一致；
//  Outlined 变体用原名，Filled 变体加 Filled 后缀——不强行把 Filled 统一成
//  Outlined，避免迁移时引入视觉回归（同一个图标在两处分别用 Filled/Outlined
//  是有意的视觉差异，收口只集中引用入口，不改视觉）。
//  AutoMirrored 图标（ArrowBack/Send/MenuBook 等 RTL 感知图标）去掉 AutoMirrored
//  前缀直接用原名，调用方无需感知镜像细节。
// ─────────────────────────────────────────────────────────────

object AppIcons {

    // ── 通用操作图标 ──────────────────────────────────────────
    val Add: ImageVector               = Icons.Outlined.Add
    val AddFilled: ImageVector         = Icons.Filled.Add
    val Check: ImageVector             = Icons.Outlined.Check
    val Copy: ImageVector              = Icons.Outlined.ContentCopy
    val TextSelect: ImageVector        = Icons.Outlined.TextFields
    val Close: ImageVector             = Icons.Outlined.Close
    val CloseFilled: ImageVector       = Icons.Filled.Close
    val Cancel: ImageVector            = Icons.Outlined.Cancel
    val Edit: ImageVector              = Icons.Outlined.Edit
    val Delete: ImageVector            = Icons.Outlined.Delete
    val DeleteOutline: ImageVector     = Icons.Outlined.DeleteOutline
    val DeleteSweep: ImageVector       = Icons.Outlined.DeleteSweep
    val Share: ImageVector             = Icons.Outlined.Share
    val Download: ImageVector          = Icons.Outlined.Download
    val OpenInNew: ImageVector         = Icons.Outlined.OpenInNew
    val ExpandMore: ImageVector        = Icons.Outlined.ExpandMore
    val MoreVert: ImageVector          = Icons.Outlined.MoreVert
    val Search: ImageVector            = Icons.Outlined.Search
    val Settings: ImageVector          = Icons.Outlined.Settings
    val Refresh: ImageVector           = Icons.Outlined.Refresh
    val Restore: ImageVector           = Icons.Outlined.Restore
    val History: ImageVector           = Icons.Outlined.History
    val FilterList: ImageVector        = Icons.Outlined.FilterList
    val ChevronRight: ImageVector      = Icons.Outlined.ChevronRight
    val KeyboardArrowDown: ImageVector = Icons.Outlined.KeyboardArrowDown
    val KeyboardArrowUp: ImageVector   = Icons.Outlined.KeyboardArrowUp
    val UnfoldMore: ImageVector        = Icons.Outlined.UnfoldMore
    val UnfoldLess: ImageVector        = Icons.Outlined.UnfoldLess
    val Lock: ImageVector              = Icons.Outlined.Lock
    val Block: ImageVector             = Icons.Outlined.Block
    val Info: ImageVector              = Icons.Outlined.Info
    val CameraAlt: ImageVector         = Icons.Outlined.CameraAlt
    val Wallpaper: ImageVector         = Icons.Outlined.Wallpaper
    val AccountCircle: ImageVector     = Icons.Outlined.AccountCircle

    // ── 导航/方向图标（AutoMirrored，RTL 感知）────────────────
    val ArrowBack: ImageVector         = Icons.AutoMirrored.Outlined.ArrowBack
    val ArrowBackFilled: ImageVector   = Icons.AutoMirrored.Filled.ArrowBack
    val ArrowForward: ImageVector      = Icons.AutoMirrored.Outlined.ArrowForward
    val Send: ImageVector              = Icons.AutoMirrored.Outlined.Send
    val MenuBook: ImageVector          = Icons.AutoMirrored.Outlined.MenuBook
    val TrendingUp: ImageVector        = Icons.AutoMirrored.Outlined.TrendingUp
    val Assignment: ImageVector        = Icons.AutoMirrored.Outlined.Assignment
    val Notes: ImageVector             = Icons.AutoMirrored.Outlined.Notes

    // ── 状态/选择图标 ─────────────────────────────────────────
    val CheckCircle: ImageVector       = Icons.Outlined.CheckCircle
    val CheckCircleFilled: ImageVector = Icons.Filled.CheckCircle
    val Circle: ImageVector            = Icons.Outlined.Circle
    val CheckBox: ImageVector          = Icons.Outlined.CheckBox
    val CheckBoxOutlineBlank: ImageVector = Icons.Outlined.CheckBoxOutlineBlank
    val RadioButtonUnchecked: ImageVector  = Icons.Outlined.RadioButtonUnchecked
    val Error: ImageVector             = Icons.Outlined.Error
    val ErrorOutline: ImageVector      = Icons.Outlined.ErrorOutline
    val HourglassEmpty: ImageVector    = Icons.Outlined.HourglassEmpty
    val Pending: ImageVector           = Icons.Outlined.Pending
    val Bolt: ImageVector              = Icons.Outlined.Bolt
    val Warning: ImageVector           = Icons.Outlined.Warning
    val Shield: ImageVector            = Icons.Outlined.Shield
    val Star: ImageVector              = Icons.Outlined.Star
    val StarBorder: ImageVector        = Icons.Outlined.StarBorder

    // ── 文件夹/文件类型图标 ───────────────────────────────────
    val Folder: ImageVector            = Icons.Outlined.Folder
    val FolderOpen: ImageVector        = Icons.Outlined.FolderOpen
    val FolderOpenFilled: ImageVector  = Icons.Filled.FolderOpen
    val PictureAsPdf: ImageVector      = Icons.Outlined.PictureAsPdf
    val Description: ImageVector       = Icons.Outlined.Description
    val Code: ImageVector              = Icons.Outlined.Code
    val TableChart: ImageVector        = Icons.Outlined.TableChart
    val Email: ImageVector             = Icons.Outlined.Email

    // ── 项目/任务流程图标 ─────────────────────────────────────
    val PauseCircle: ImageVector       = Icons.Outlined.PauseCircle
    val PlayCircle: ImageVector        = Icons.Outlined.PlayCircle
    val PlayArrow: ImageVector         = Icons.Outlined.PlayArrow
    val Archive: ImageVector           = Icons.Outlined.Archive
    val Spa: ImageVector               = Icons.Outlined.Spa
    val Schedule: ImageVector          = Icons.Outlined.Schedule
    val Event: ImageVector             = Icons.Outlined.Event
    val CalendarMonth: ImageVector     = Icons.Outlined.CalendarMonth
    val Build: ImageVector             = Icons.Outlined.Build
    val Work: ImageVector              = Icons.Outlined.Work
    val Flag: ImageVector              = Icons.Outlined.Flag

    // ── 角色成长/特长/竞技图标 ────────────────────────────────
    val EmojiEvents: ImageVector       = Icons.Outlined.EmojiEvents
    val Favorite: ImageVector          = Icons.Outlined.Favorite
    val AutoAwesome: ImageVector       = Icons.Outlined.AutoAwesome
    val Lightbulb: ImageVector         = Icons.Outlined.Lightbulb
    val TipsAndUpdates: ImageVector    = Icons.Outlined.TipsAndUpdates
    val School: ImageVector            = Icons.Outlined.School
    val SelfImprovement: ImageVector   = Icons.Outlined.SelfImprovement
    val Psychology: ImageVector        = Icons.Outlined.Psychology
    val Gavel: ImageVector             = Icons.Outlined.Gavel
    val Memory: ImageVector            = Icons.Outlined.Memory
    val SmartToy: ImageVector          = Icons.Outlined.SmartToy
    val AutoMode: ImageVector          = Icons.Outlined.AutoMode
    val Speed: ImageVector             = Icons.Outlined.Speed
    val SentimentSatisfied: ImageVector   = Icons.Outlined.SentimentSatisfied
    val SentimentDissatisfied: ImageVector = Icons.Outlined.SentimentDissatisfied

    // ── 通知/首页图标 ─────────────────────────────────────────
    val Notifications: ImageVector    = Icons.Outlined.Notifications
    val Home: ImageVector             = Icons.Outlined.Home
    val Person: ImageVector           = Icons.Outlined.Person

    // ── 工具图标（CharacterDetailAbility 固定枚举）────────────
    val ToolSearch: ImageVector      = Icons.Outlined.Search
    val ToolDescription: ImageVector = Icons.Outlined.Description
    val ToolCode: ImageVector        = Icons.Outlined.Code
    val ToolTable: ImageVector       = Icons.Outlined.TableChart
    val ToolEmail: ImageVector       = Icons.Outlined.Email

    // ── 文件类型图标映射（共享，原 FileVaultScreen.kt 私有 fileIcon 提取） ──
    // 细化方案第四节：fileIcon() 从 private 改为共享，ContentBlockRenderer /
    // ChatMessageBubble 复用同一份映射。同时让 "table" 也映射到 TableChart，
    // 修复 TableFileBlockRenderer 传 "table" 却落到 else 分支通用文档图标的 bug。
    // zip 改用 Archive（已定义但未使用，语义比 Folder 更准确）。
    fun fileIconForType(fileType: String): ImageVector = when (fileType.lowercase()) {
        "xlsx", "csv", "table"                  -> TableChart
        "pdf"                                   -> PictureAsPdf
        "md", "txt", "html", "htm", "json", "xml", "log", "yml", "yaml" -> Code
        "zip", "rar", "7z", "tar", "gz"         -> Archive
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> Description
        else                                    -> Description
    }

    // ── 文件类型语义色（icon_redesign_renders 新配色方案，取代细化方案第二节初版） ──
    // 项目此前没有为"文件类型"维度定义语义色（Palette.Semantic* 是状态色，
    // ownerAccent 是角色色）。这组颜色只用于图标槽 tint，不影响外壳的渐变/描边。
    // 初版把 PDF/文档/压缩包全部归到 Ink600 同一色，观感单一；现按类型分派独立
    // 暖色系色相（与 fileIconForType 的分组一一对应）。
    fun fileTypeSemanticColor(fileType: String): Color = when (fileType.lowercase()) {
        "pdf"                                                             -> Palette.FileTypePdf    // PDF → 赭红
        "md", "txt", "html", "htm", "json", "xml", "log", "yml", "yaml"   -> Palette.FileTypeDoc    // 文档/MD → 靛蓝灰
        "xlsx", "csv", "table"                                            -> Palette.FileTypeTable  // 表格 → 青竹
        "zip", "rar", "7z", "tar", "gz"                                   -> Palette.FileTypeZip    // 压缩包 → 橄榄棕
        "jpg", "jpeg", "png", "gif", "webp", "bmp"                        -> Palette.FileTypeImage  // 图片 → 琥珀
        "link", "url"                                                     -> Palette.Gold           // 链接 → 沿用既有黄铜金
        else                                                              -> Palette.FileTypeDoc    // 未识别类型兜底
    }
}

/**
 * 图标 + 圆角小色块背景（精修方案 v2.1 2.4）。
 *
 * 取代"默认灰/无背景"的裸 Icon，统一套 Radius.xs 圆角小色块背景。
 * 背景色默认取 colors.accentSoft（主题感知的强调色柔和版）；
 * 语义色场景（如成功/失败/警告）传入对应 Palette.SemanticXxx.copy(alpha = 0.12f)。
 *
 * @param icon      图标本体
 * @param contentDescription 无障碍描述
 * @param modifier  布局修饰符
 * @param tint      图标颜色（默认 colors.accent）
 * @param background 色块背景色（默认 colors.accentSoft）
 * @param size      图标本身尺寸（不含色块内边距）
 * @param badgeSize 色块整体尺寸；null 时按 size + 内边距自适应
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    background: Color? = null,
    size: Dp = 16.dp,
    badgeSize: Dp? = null,
) {
    val colors = ZaijianTheme.colors
    val resolvedTint = tint ?: colors.accent
    val resolvedBg   = background ?: colors.accentSoft

    Box(
        modifier = modifier
            .let { if (badgeSize != null) it.size(badgeSize) else it }
            .clip(RoundedCornerShape(Radius.xs))
            .background(resolvedBg)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            modifier           = Modifier.size(size),
            tint               = resolvedTint,
        )
    }
}
