package com.rrajath.grove.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Full Grove design-token palette from design/README.md. Material's colorScheme
 * only covers a subset of these, so the complete set is exposed through
 * [LocalGroveColors] / `MaterialTheme.grove`.
 */
@Immutable
data class GroveColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val line2: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val green: Color,
    val greenSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val red: Color,
    val redSoft: Color,
    val blue: Color,
    val blueSoft: Color,
    val violet: Color,
    /** ~0.13 tint of [violet] — background for the bare active-timestamp event chip. */
    val violetSoft: Color,
    // Org syntax highlighting
    val synStar: Color,
    val synTodo: Color,
    val synDone: Color,
    val synKw: Color,
    val synTs: Color,
    val synTag: Color,
    val synLink: Color,
    val synProp: Color,
    val isDark: Boolean,
)

val GroveLightColors = GroveColors(
    bg = Color(0xFFF3EDE1),
    surface = Color(0xFFFBF8F1),
    surface2 = Color(0xFFECE4D5),
    surface3 = Color(0xFFE3D9C6),
    ink = Color(0xFF2A251F),
    ink2 = Color(0xFF6C6356),
    ink3 = Color(0xFF9C9384),
    line = Color(0xFFE3DBCB),
    line2 = Color(0xFFD3C9B6),
    accent = Color(0xFF8A5A2B),
    accentInk = Color(0xFFFFFAF2),
    accentSoft = Color(0x1F8A5A2B), // rgba(138,90,43,0.12)
    green = Color(0xFF4F7A3A),
    greenSoft = Color(0x244F7A3A), // 0.14
    amber = Color(0xFFA9761D),
    amberSoft = Color(0x29A9761D), // 0.16
    red = Color(0xFFA5462F),
    redSoft = Color(0x21A5462F), // 0.13
    blue = Color(0xFF3F6F86),
    blueSoft = Color(0x213F6F86), // 0.13
    violet = Color(0xFF6E5A8E),
    violetSoft = Color(0x216E5A8E), // 0.13
    synStar = Color(0xFF4F7A3A),
    synTodo = Color(0xFFA9761D),
    synDone = Color(0xFF4F7A3A),
    synKw = Color(0xFF6E5A8E), // = violet; was a gray indistinguishable from ink2
    synTs = Color(0xFF3F6F86),
    synTag = Color(0xFF8A5A2B),
    synLink = Color(0xFF3F6F86),
    synProp = Color(0xFFA39A89),
    isDark = false,
)

val GroveDarkColors = GroveColors(
    bg = Color(0xFF16130E),
    surface = Color(0xFF201C15),
    surface2 = Color(0xFF2A251B),
    surface3 = Color(0xFF352F23),
    ink = Color(0xFFECE4D5),
    ink2 = Color(0xFFB4A98F),
    ink3 = Color(0xFF7C7460),
    line = Color(0xFF322C20),
    line2 = Color(0xFF433A2B),
    accent = Color(0xFFCB9D62),
    accentInk = Color(0xFF1A160D),
    accentSoft = Color(0x2ECB9D62), // 0.18
    green = Color(0xFF8FB46A),
    greenSoft = Color(0x2B8FB46A), // 0.17
    amber = Color(0xFFD7A64F),
    amberSoft = Color(0x2BD7A64F), // 0.17
    red = Color(0xFFD2856A),
    redSoft = Color(0x29D2856A), // 0.16
    blue = Color(0xFF7FB0C4),
    blueSoft = Color(0x297FB0C4), // 0.16
    violet = Color(0xFFAD9BD1),
    violetSoft = Color(0x29AD9BD1), // 0.16
    synStar = Color(0xFF8FB46A),
    synTodo = Color(0xFFD7A64F),
    synDone = Color(0xFF8FB46A),
    synKw = Color(0xFFAD9BD1), // = violet; was ink2, indistinguishable from key/value text
    synTs = Color(0xFF7FB0C4),
    synTag = Color(0xFFCB9D62),
    synLink = Color(0xFF7FB0C4),
    synProp = Color(0xFF7C7460),
    isDark = true,
)

val GroveTokyoNightColors = GroveColors(
    bg = Color(0xFF1A1B26),
    surface = Color(0xFF1F2335),
    surface2 = Color(0xFF24283B),
    surface3 = Color(0xFF292E42),
    ink = Color(0xFFC0CAF5),
    ink2 = Color(0xFF9AA5CE),
    ink3 = Color(0xFF565F89),
    line = Color(0xFF232741),
    line2 = Color(0xFF2F344D),
    accent = Color(0xFF7AA2F7),
    accentInk = Color(0xFF16161E),
    accentSoft = Color(0x297AA2F7), // 0.16
    green = Color(0xFF9ECE6A),
    greenSoft = Color(0x299ECE6A), // 0.16
    amber = Color(0xFFE0AF68),
    amberSoft = Color(0x29E0AF68), // 0.16
    red = Color(0xFFF7768E),
    redSoft = Color(0x29F7768E), // 0.16
    blue = Color(0xFF7DCFFF),
    blueSoft = Color(0x297DCFFF), // 0.16
    violet = Color(0xFFBB9AF7),
    violetSoft = Color(0x29BB9AF7), // 0.16
    synStar = Color(0xFFBB9AF7),
    synTodo = Color(0xFFE0AF68),
    synDone = Color(0xFF9ECE6A),
    synKw = Color(0xFF7DCFFF),
    synTs = Color(0xFF73DACA),
    synTag = Color(0xFFBB9AF7),
    synLink = Color(0xFF7AA2F7),
    synProp = Color(0xFF565F89),
    isDark = true,
)

val GroveCatppuccinColors = GroveColors(
    bg = Color(0xFF1E1E2E),
    surface = Color(0xFF292A3D),
    surface2 = Color(0xFF313244),
    surface3 = Color(0xFF45475A),
    ink = Color(0xFFCDD6F4),
    ink2 = Color(0xFFA6ADC8),
    ink3 = Color(0xFF6C7086),
    line = Color(0xFF2C2D40),
    line2 = Color(0xFF3A3C50),
    accent = Color(0xFFCBA6F7),
    accentInk = Color(0xFF181825),
    accentSoft = Color(0x29CBA6F7), // 0.16
    green = Color(0xFFA6E3A1),
    greenSoft = Color(0x29A6E3A1), // 0.16
    amber = Color(0xFFFAB387),
    amberSoft = Color(0x29FAB387), // 0.16
    red = Color(0xFFF38BA8),
    redSoft = Color(0x29F38BA8), // 0.16
    blue = Color(0xFF89B4FA),
    blueSoft = Color(0x2989B4FA), // 0.16
    violet = Color(0xFFF5C2E7),
    violetSoft = Color(0x29F5C2E7), // 0.16
    synStar = Color(0xFFCBA6F7),
    synTodo = Color(0xFFFAB387),
    synDone = Color(0xFFA6E3A1),
    synKw = Color(0xFFF5C2E7),
    synTs = Color(0xFF94E2D5),
    synTag = Color(0xFFCBA6F7),
    synLink = Color(0xFF89B4FA),
    synProp = Color(0xFF6C7086),
    isDark = true,
)

val GroveTokyoDayColors = GroveColors(
    bg = Color(0xFFE1E2E7),
    surface = Color(0xFFEDEEF2),
    surface2 = Color(0xFFD5D6DB),
    surface3 = Color(0xFFC4C8DA),
    ink = Color(0xFF3760BF),
    ink2 = Color(0xFF5A638F),
    ink3 = Color(0xFF9699A3),
    line = Color(0xFFC4C8DA),
    line2 = Color(0xFFA1A6C5),
    accent = Color(0xFF2E7DE9),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x292E7DE9), // 0.16
    green = Color(0xFF587539),
    greenSoft = Color(0x29587539), // 0.16
    amber = Color(0xFFB15C00),
    amberSoft = Color(0x29B15C00), // 0.16
    red = Color(0xFFF52A65),
    redSoft = Color(0x29F52A65), // 0.16
    blue = Color(0xFF007197),
    blueSoft = Color(0x29007197), // 0.16
    violet = Color(0xFF9854F1),
    violetSoft = Color(0x299854F1), // 0.16
    synStar = Color(0xFF9854F1),
    synTodo = Color(0xFFB15C00),
    synDone = Color(0xFF587539),
    synKw = Color(0xFF007197), // = blue; was ink2, indistinguishable from key/value text
    synTs = Color(0xFF007197),
    synTag = Color(0xFF2E7DE9),
    synLink = Color(0xFF007197),
    synProp = Color(0xFF9699A3),
    isDark = false,
)

val GroveCatppuccinLatteColors = GroveColors(
    bg = Color(0xFFEFF1F5),
    surface = Color(0xFFE6E9EF),
    surface2 = Color(0xFFCCD0DA),
    surface3 = Color(0xFFBCC0CC),
    ink = Color(0xFF4C4F69),
    ink2 = Color(0xFF6C6F85),
    ink3 = Color(0xFF9CA0B0),
    line = Color(0xFFDCE0E8),
    line2 = Color(0xFFACB0BE),
    accent = Color(0xFF8839EF),
    accentInk = Color(0xFFEFF1F5),
    accentSoft = Color(0x1F8839EF), // 0.12
    green = Color(0xFF40A02B),
    greenSoft = Color(0x2440A02B), // 0.14
    amber = Color(0xFFDF8E1D),
    amberSoft = Color(0x29DF8E1D), // 0.16
    red = Color(0xFFD20F39),
    redSoft = Color(0x21D20F39), // 0.13
    blue = Color(0xFF1E66F5),
    blueSoft = Color(0x211E66F5), // 0.13
    violet = Color(0xFF7287FD),
    violetSoft = Color(0x217287FD), // 0.13
    synStar = Color(0xFF8839EF),
    synTodo = Color(0xFFDF8E1D),
    synDone = Color(0xFF40A02B),
    synKw = Color(0xFF7287FD), // = violet; was ink2, indistinguishable from key/value text
    synTs = Color(0xFF1E66F5),
    synTag = Color(0xFF8839EF),
    synLink = Color(0xFF1E66F5),
    synProp = Color(0xFF9CA0B0),
    isDark = false,
)

val GroveRosePineDawnColors = GroveColors(
    bg = Color(0xFFFAF4ED),
    surface = Color(0xFFFFFAF3),
    surface2 = Color(0xFFF2E9E1),
    surface3 = Color(0xFFDFDAD9),
    ink = Color(0xFF575279),
    ink2 = Color(0xFF797593),
    ink3 = Color(0xFF9893A5),
    line = Color(0xFFF4EDE8),
    line2 = Color(0xFFCECACD),
    accent = Color(0xFF907AA9),
    accentInk = Color(0xFFFAF4ED),
    accentSoft = Color(0x1F907AA9), // 0.12
    green = Color(0xFF286983),
    greenSoft = Color(0x24286983), // 0.14
    amber = Color(0xFFEA9D34),
    amberSoft = Color(0x29EA9D34), // 0.16
    red = Color(0xFFB4637A),
    redSoft = Color(0x21B4637A), // 0.13
    blue = Color(0xFF56949F),
    blueSoft = Color(0x2156949F), // 0.13
    violet = Color(0xFF907AA9),
    violetSoft = Color(0x21907AA9), // 0.13
    synStar = Color(0xFF907AA9),
    synTodo = Color(0xFFEA9D34),
    synDone = Color(0xFF286983),
    synKw = Color(0xFF56949F), // = blue; was ink2, indistinguishable from key/value text
    synTs = Color(0xFF56949F),
    synTag = Color(0xFFD7827E),
    synLink = Color(0xFF56949F),
    synProp = Color(0xFF9893A5),
    isDark = false,
)

val GroveRosePineMoonColors = GroveColors(
    bg = Color(0xFF232136),
    surface = Color(0xFF2A273F),
    surface2 = Color(0xFF393552),
    surface3 = Color(0xFF44415A),
    ink = Color(0xFFE0DEF4),
    ink2 = Color(0xFF908CAA),
    ink3 = Color(0xFF6E6A86),
    line = Color(0xFF2A283E),
    line2 = Color(0xFF56526E),
    accent = Color(0xFFC4A7E7),
    accentInk = Color(0xFF232136),
    accentSoft = Color(0x2EC4A7E7), // 0.18
    green = Color(0xFF3E8FB0),
    greenSoft = Color(0x2B3E8FB0), // 0.17
    amber = Color(0xFFF6C177),
    amberSoft = Color(0x2BF6C177), // 0.17
    red = Color(0xFFEB6F92),
    redSoft = Color(0x29EB6F92), // 0.16
    blue = Color(0xFF9CCFD8),
    blueSoft = Color(0x299CCFD8), // 0.16
    violet = Color(0xFFC4A7E7),
    violetSoft = Color(0x29C4A7E7), // 0.16
    synStar = Color(0xFFC4A7E7),
    synTodo = Color(0xFFF6C177),
    synDone = Color(0xFF3E8FB0),
    synKw = Color(0xFF9CCFD8), // = blue; was ink2, indistinguishable from key/value text
    synTs = Color(0xFF9CCFD8),
    synTag = Color(0xFFEA9A97),
    synLink = Color(0xFF9CCFD8),
    synProp = Color(0xFF6E6A86),
    isDark = true,
)

val GroveChalkColors = GroveColors(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF4F5F6),
    surface3 = Color(0xFFE9EBED),
    ink = Color(0xFF1C1F22),
    ink2 = Color(0xFF5C6469),
    ink3 = Color(0xFF949BA1),
    line = Color(0xFFE8EAEC),
    line2 = Color(0xFFD8DCDF),
    accent = Color(0xFF3F4A52),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x173F4A52), // 0.09
    green = Color(0xFF4A6B56),
    greenSoft = Color(0x1C4A6B56), // 0.11
    amber = Color(0xFF8A7434),
    amberSoft = Color(0x1F8A7434), // 0.12
    red = Color(0xFF96534C),
    redSoft = Color(0x1A96534C), // 0.10
    blue = Color(0xFF4A647D),
    blueSoft = Color(0x1A4A647D), // 0.10
    violet = Color(0xFF714B89),
    violetSoft = Color(0x1A714B89), // 0.10
    synStar = Color(0xFF4A6B56),
    synTodo = Color(0xFF8A7434),
    synDone = Color(0xFF4A6B56),
    synKw = Color(0xFF714B89), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF4A647D),
    synTag = Color(0xFF3F4A52),
    synLink = Color(0xFF4A647D),
    synProp = Color(0xFFA0A7AC),
    isDark = false,
)

val GroveCobaltColors = GroveColors(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF2F5F9),
    surface3 = Color(0xFFE6ECF4),
    ink = Color(0xFF151A21),
    ink2 = Color(0xFF535E6B),
    ink3 = Color(0xFF8B94A1),
    line = Color(0xFFE6EAF0),
    line2 = Color(0xFFD4DAE4),
    accent = Color(0xFF1D5AA8),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x1A1D5AA8), // 0.10
    green = Color(0xFF3F6F52),
    greenSoft = Color(0x1C3F6F52), // 0.11
    amber = Color(0xFF8A6F2C),
    amberSoft = Color(0x1F8A6F2C), // 0.12
    red = Color(0xFF9B4B46),
    redSoft = Color(0x1A9B4B46), // 0.10
    blue = Color(0xFF1D5AA8),
    blueSoft = Color(0x1C1D5AA8), // 0.11
    violet = Color(0xFF7730A3),
    violetSoft = Color(0x1C7730A3), // 0.11
    synStar = Color(0xFF3F6F52),
    synTodo = Color(0xFF8A6F2C),
    synDone = Color(0xFF3F6F52),
    synKw = Color(0xFF7730A3), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF1D5AA8),
    synTag = Color(0xFF1D5AA8),
    synLink = Color(0xFF1D5AA8),
    synProp = Color(0xFF96A0AD),
    isDark = false,
)

val GroveDelftColors = GroveColors(
    bg = Color(0xFFEEF1F6),
    surface = Color(0xFFF7F9FC),
    surface2 = Color(0xFFE5EAF2),
    surface3 = Color(0xFFDAE1EC),
    ink = Color(0xFF1A222D),
    ink2 = Color(0xFF55606E),
    ink3 = Color(0xFF8B95A3),
    line = Color(0xFFDDE3EC),
    line2 = Color(0xFFCCD4E0),
    accent = Color(0xFF2A5D9E),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x1C2A5D9E), // 0.11
    green = Color(0xFF456F57),
    greenSoft = Color(0x1F456F57), // 0.12
    amber = Color(0xFF8A7030),
    amberSoft = Color(0x218A7030), // 0.13
    red = Color(0xFF9A504A),
    redSoft = Color(0x1C9A504A), // 0.11
    blue = Color(0xFF2A5D9E),
    blueSoft = Color(0x1F2A5D9E), // 0.12
    violet = Color(0xFF77399D),
    violetSoft = Color(0x1F77399D), // 0.12
    synStar = Color(0xFF456F57),
    synTodo = Color(0xFF8A7030),
    synDone = Color(0xFF456F57),
    synKw = Color(0xFF77399D), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF2A5D9E),
    synTag = Color(0xFF2A5D9E),
    synLink = Color(0xFF2A5D9E),
    synProp = Color(0xFF96A0AE),
    isDark = false,
)

val GroveSageColors = GroveColors(
    bg = Color(0xFFF6F8F4),
    surface = Color(0xFFFCFDFB),
    surface2 = Color(0xFFEEF2EA),
    surface3 = Color(0xFFE3E9DE),
    ink = Color(0xFF1F2622),
    ink2 = Color(0xFF5A6560),
    ink3 = Color(0xFF939C96),
    line = Color(0xFFE2E8DE),
    line2 = Color(0xFFD2DBCC),
    accent = Color(0xFF4E6B53),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x1A4E6B53), // 0.10
    green = Color(0xFF4E6B53),
    greenSoft = Color(0x1F4E6B53), // 0.12
    amber = Color(0xFF8A7333),
    amberSoft = Color(0x1F8A7333), // 0.12
    red = Color(0xFF97564D),
    redSoft = Color(0x1A97564D), // 0.10
    blue = Color(0xFF4C6A78),
    blueSoft = Color(0x1A4C6A78), // 0.10
    violet = Color(0xFF714D87),
    violetSoft = Color(0x1A714D87), // 0.10
    synStar = Color(0xFF4E6B53),
    synTodo = Color(0xFF8A7333),
    synDone = Color(0xFF4E6B53),
    synKw = Color(0xFF714D87), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF4C6A78),
    synTag = Color(0xFF4E6B53),
    synLink = Color(0xFF4C6A78),
    synProp = Color(0xFF9BA49E),
    isDark = false,
)

val GroveAshRoseColors = GroveColors(
    bg = Color(0xFFFAF6F6),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF3ECEE),
    surface3 = Color(0xFFEAE0E2),
    ink = Color(0xFF241F21),
    ink2 = Color(0xFF61585B),
    ink3 = Color(0xFF9A9093),
    line = Color(0xFFECE5E6),
    line2 = Color(0xFFDED3D5),
    accent = Color(0xFF7D5560),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x1A7D5560), // 0.10
    green = Color(0xFF526B54),
    greenSoft = Color(0x1C526B54), // 0.11
    amber = Color(0xFF8A6F36),
    amberSoft = Color(0x1F8A6F36), // 0.12
    red = Color(0xFF96504F),
    redSoft = Color(0x1A96504F), // 0.10
    blue = Color(0xFF4D6478),
    blueSoft = Color(0x1A4D6478), // 0.10
    violet = Color(0xFF714E87),
    violetSoft = Color(0x1A714E87), // 0.10
    synStar = Color(0xFF526B54),
    synTodo = Color(0xFF8A6F36),
    synDone = Color(0xFF526B54),
    synKw = Color(0xFF714E87), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF4D6478),
    synTag = Color(0xFF7D5560),
    synLink = Color(0xFF4D6478),
    synProp = Color(0xFFA3999C),
    isDark = false,
)

val GroveObsidianColors = GroveColors(
    bg = Color(0xFF000000),
    surface = Color(0xFF0C0D0E),
    surface2 = Color(0xFF141618),
    surface3 = Color(0xFF1D2023),
    ink = Color(0xFFE6E8EA),
    ink2 = Color(0xFF9AA1A7),
    ink3 = Color(0xFF6A7176),
    line = Color(0xFF1A1C1F),
    line2 = Color(0xFF272B2F),
    accent = Color(0xFF9FB0BB),
    accentInk = Color(0xFF0A0C0D),
    accentSoft = Color(0x219FB0BB), // 0.13
    green = Color(0xFF86A58E),
    greenSoft = Color(0x2186A58E), // 0.13
    amber = Color(0xFFC2A86E),
    amberSoft = Color(0x21C2A86E), // 0.13
    red = Color(0xFFC98A80),
    redSoft = Color(0x21C98A80), // 0.13
    blue = Color(0xFF8FABC0),
    blueSoft = Color(0x218FABC0), // 0.13
    violet = Color(0xFFAD88C4),
    violetSoft = Color(0x21AD88C4), // 0.13
    synStar = Color(0xFF86A58E),
    synTodo = Color(0xFFC2A86E),
    synDone = Color(0xFF86A58E),
    synKw = Color(0xFFAD88C4), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF8FABC0),
    synTag = Color(0xFF9FB0BB),
    synLink = Color(0xFF8FABC0),
    synProp = Color(0xFF6A7176),
    isDark = true,
)

val GroveMossColors = GroveColors(
    bg = Color(0xFF000000),
    surface = Color(0xFF0B0D0B),
    surface2 = Color(0xFF131711),
    surface3 = Color(0xFF1C211A),
    ink = Color(0xFFE2E8DE),
    ink2 = Color(0xFF98A394),
    ink3 = Color(0xFF687063),
    line = Color(0xFF191D18),
    line2 = Color(0xFF262C24),
    accent = Color(0xFF8FAA87),
    accentInk = Color(0xFF0A0D09),
    accentSoft = Color(0x218FAA87), // 0.13
    green = Color(0xFF8FAA87),
    greenSoft = Color(0x218FAA87), // 0.13
    amber = Color(0xFFC0A76C),
    amberSoft = Color(0x21C0A76C), // 0.13
    red = Color(0xFFC68B7F),
    redSoft = Color(0x21C68B7F), // 0.13
    blue = Color(0xFF86A8AD),
    blueSoft = Color(0x2186A8AD), // 0.13
    violet = Color(0xFFA582BA),
    violetSoft = Color(0x21A582BA), // 0.13
    synStar = Color(0xFF8FAA87),
    synTodo = Color(0xFFC0A76C),
    synDone = Color(0xFF8FAA87),
    synKw = Color(0xFFA582BA), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF86A8AD),
    synTag = Color(0xFF8FAA87),
    synLink = Color(0xFF86A8AD),
    synProp = Color(0xFF687063),
    isDark = true,
)

val GroveLanternColors = GroveColors(
    bg = Color(0xFF17181A),
    surface = Color(0xFF1D1F21),
    surface2 = Color(0xFF232527),
    surface3 = Color(0xFF2B2E31),
    ink = Color(0xFFE8E6E2),
    ink2 = Color(0xFFA0A09C),
    ink3 = Color(0xFF71716D),
    line = Color(0xFF26282A),
    line2 = Color(0xFF33363A),
    accent = Color(0xFFE0C069),
    accentInk = Color(0xFF17181A),
    accentSoft = Color(0x21E0C069), // 0.13
    green = Color(0xFF8FAE86),
    greenSoft = Color(0x218FAE86), // 0.13
    amber = Color(0xFFE0C069),
    amberSoft = Color(0x21E0C069), // 0.13
    red = Color(0xFFCB8A7F),
    redSoft = Color(0x21CB8A7F), // 0.13
    blue = Color(0xFF8AA8BD),
    blueSoft = Color(0x218AA8BD), // 0.13
    violet = Color(0xFFAC84C4),
    violetSoft = Color(0x21AC84C4), // 0.13
    synStar = Color(0xFFE0C069),
    synTodo = Color(0xFFE0C069),
    synDone = Color(0xFF8FAE86),
    synKw = Color(0xFFAC84C4), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF8AA8BD),
    synTag = Color(0xFFE0C069),
    synLink = Color(0xFF8AA8BD),
    synProp = Color(0xFF71716D),
    isDark = true,
)

val GroveWisteriaColors = GroveColors(
    bg = Color(0xFF131019),
    surface = Color(0xFF1A1622),
    surface2 = Color(0xFF211D2B),
    surface3 = Color(0xFF292435),
    ink = Color(0xFFE6E2EC),
    ink2 = Color(0xFF9D97A9),
    ink3 = Color(0xFF6D6878),
    line = Color(0xFF241F2E),
    line2 = Color(0xFF322C3F),
    accent = Color(0xFFA99BC4),
    accentInk = Color(0xFF0B0A0E),
    accentSoft = Color(0x21A99BC4), // 0.13
    green = Color(0xFF8BA88F),
    greenSoft = Color(0x218BA88F), // 0.13
    amber = Color(0xFFC1A674),
    amberSoft = Color(0x21C1A674), // 0.13
    red = Color(0xFFC68D92),
    redSoft = Color(0x21C68D92), // 0.13
    blue = Color(0xFF8FA6C6),
    blueSoft = Color(0x218FA6C6), // 0.13
    violet = Color(0xFFB18EC6),
    violetSoft = Color(0x21B18EC6), // 0.13
    synStar = Color(0xFFA99BC4),
    synTodo = Color(0xFFC1A674),
    synDone = Color(0xFF8BA88F),
    synKw = Color(0xFFB18EC6), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF8FA6C6),
    synTag = Color(0xFFA99BC4),
    synLink = Color(0xFF8FA6C6),
    synProp = Color(0xFF6D6878),
    isDark = true,
)

val GroveLighthouseColors = GroveColors(
    bg = Color(0xFF0F1723),
    surface = Color(0xFF142032),
    surface2 = Color(0xFF1A2739),
    surface3 = Color(0xFF223047),
    ink = Color(0xFFE3E8EF),
    ink2 = Color(0xFF94A1B3),
    ink3 = Color(0xFF667487),
    line = Color(0xFF1C2839),
    line2 = Color(0xFF2A3A50),
    accent = Color(0xFFE8C777),
    accentInk = Color(0xFF10192A),
    accentSoft = Color(0x21E8C777), // 0.13
    green = Color(0xFF8BB08F),
    greenSoft = Color(0x218BB08F), // 0.13
    amber = Color(0xFFE8C777),
    amberSoft = Color(0x21E8C777), // 0.13
    red = Color(0xFFD08A83),
    redSoft = Color(0x21D08A83), // 0.13
    blue = Color(0xFF86A9CC),
    blueSoft = Color(0x2186A9CC), // 0.13
    violet = Color(0xFFB284CE),
    violetSoft = Color(0x21B284CE), // 0.13
    synStar = Color(0xFFE8C777),
    synTodo = Color(0xFFE8C777),
    synDone = Color(0xFF8BB08F),
    synKw = Color(0xFFB284CE), // = violet; was too close to synProp (property values)
    synTs = Color(0xFF86A9CC),
    synTag = Color(0xFFE8C777),
    synLink = Color(0xFF86A9CC),
    synProp = Color(0xFF667487),
    isDark = true,
)

// Bloom Light / Bloom Dark: the "Rosewater" pair (rosew-l / rosew-d) from
// the Bloom App.dc.html design. `violet` maps from the design's --plum token.
val GroveBloomLightColors = GroveColors(
    bg = Color(0xFFF8F3EE),
    surface = Color(0xFFFCF8F4),
    surface2 = Color(0xFFF0E9E3),
    surface3 = Color(0xFFE6DDD6),
    ink = Color(0xFF4A4466),
    ink2 = Color(0xFF6E6888),
    ink3 = Color(0xFF9893AB),
    line = Color(0xFFEBE2DB),
    line2 = Color(0xFFDDD2CA),
    accent = Color(0xFFC06A6E),
    accentInk = Color(0xFFFDF7F3),
    accentSoft = Color(0x21C06A6E), // 0.13
    green = Color(0xFF3F8A8F),
    greenSoft = Color(0x213F8A8F), // 0.13
    amber = Color(0xFFD0913A),
    amberSoft = Color(0x21D0913A), // 0.13
    red = Color(0xFFB4637A),
    redSoft = Color(0x21B4637A), // 0.13
    blue = Color(0xFF3E7FA3),
    blueSoft = Color(0x213E7FA3), // 0.13
    violet = Color(0xFF907AA9),
    violetSoft = Color(0x21907AA9), // 0.13
    synStar = Color(0xFFC06A6E),
    synTodo = Color(0xFFB4637A),
    synDone = Color(0xFF3F8A8F),
    synKw = Color(0xFF3E7FA3), // = blue; was ink2, indistinguishable from key/value text
    synTs = Color(0xFF3E7FA3),
    synTag = Color(0xFF907AA9),
    synLink = Color(0xFF3E7FA3),
    synProp = Color(0xFF9893AB),
    isDark = false,
)


val GroveBloomDarkColors = GroveColors(
    bg = Color(0xFF25223A),
    surface = Color(0xFF2B2842),
    surface2 = Color(0xFF322E4B),
    surface3 = Color(0xFF3B3756),
    ink = Color(0xFFE2DFF2),
    ink2 = Color(0xFFA9A4C4),
    ink3 = Color(0xFF736E8F),
    line = Color(0xFF34304C),
    line2 = Color(0xFF433E5D),
    accent = Color(0xFFEBA8A2),
    accentInk = Color(0xFF25223A),
    accentSoft = Color(0x29EBA8A2), // 0.16
    green = Color(0xFF9FCFC4),
    greenSoft = Color(0x299FCFC4), // 0.16
    amber = Color(0xFFF2C48D),
    amberSoft = Color(0x29F2C48D), // 0.16
    red = Color(0xFFE57D99),
    redSoft = Color(0x29E57D99), // 0.16
    blue = Color(0xFFA3D0DA),
    blueSoft = Color(0x29A3D0DA), // 0.16
    violet = Color(0xFFC4A9E7),
    violetSoft = Color(0x29C4A9E7), // 0.16
    synStar = Color(0xFFEBA8A2),
    synTodo = Color(0xFFE57D99),
    synDone = Color(0xFF9FCFC4),
    synKw = Color(0xFFC4A9E7), // = violet; was ink2, indistinguishable from key/value text
    synTs = Color(0xFFA3D0DA),
    synTag = Color(0xFFC4A9E7),
    synLink = Color(0xFFA3D0DA),
    synProp = Color(0xFF736E8F),
    isDark = true,
)

val LocalGroveColors = staticCompositionLocalOf { GroveLightColors }

/**
 * Heading-star color cycled by outline depth: green, blue, amber, red, violet,
 * brown, then back to green for deeper nesting.
 */
fun GroveColors.starColor(level: Int): Color {
    val cycle = listOf(green, blue, amber, red, violet, accent)
    return cycle[((level - 1).coerceAtLeast(0)) % cycle.size]
}

/**
 * Priority-cookie color on a red→amber→green urgency scale: A (most urgent) is
 * red, B is amber, C (least urgent) is green. The parser accepts any A-Z letter,
 * so anything past C falls back to a neutral tone rather than erroring.
 */
fun GroveColors.priorityColor(priority: Char): Color = when (priority.uppercaseChar()) {
    'A' -> red
    'B' -> amber
    'C' -> green
    else -> ink2
}

/** Soft/background counterpart of [priorityColor], for chip fills. */
fun GroveColors.prioritySoftColor(priority: Char): Color = when (priority.uppercaseChar()) {
    'A' -> redSoft
    'B' -> amberSoft
    'C' -> greenSoft
    else -> surface2
}
