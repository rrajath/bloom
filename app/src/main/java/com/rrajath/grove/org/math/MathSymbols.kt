package com.rrajath.grove.org.math

/** Font treatment for a run of math glyphs. The renderer maps each to a Compose font family/style. */
enum class MathVariant { ITALIC, UPRIGHT, BOLD, BOLD_ITALIC, SANS, MONO, TEXT }

/** TeX atom classes, which drive the spacing between neighbours (TeXbook ch. 18). */
internal enum class Atom { ORD, OP, BIN, REL, OPEN, CLOSE, PUNCT, INNER }

internal enum class AccentKind {
    HAT, WIDEHAT, CHECK, TILDE, WIDETILDE, BAR, VEC, DOT, DDOT, ACUTE, GRAVE, BREVE,
    OVERLINE, UNDERLINE, OVERRIGHTARROW, OVERLEFTARROW, OVERBRACE, UNDERBRACE,
}

internal enum class TableKind { MATRIX, CASES, ARRAY, ALIGN, GATHER, SUBSTACK }

/** Parsed LaTeX math. Produced by [LatexParser], consumed by [MathEngine]. */
internal sealed class MathNode {
    /** A run of glyphs set in one font treatment. [big] marks `\sum`-style operators that grow in display style. */
    data class Glyphs(
        val text: String,
        val atom: Atom,
        val variant: MathVariant,
        val limits: Boolean = false,
        val big: Boolean = false,
    ) : MathNode()

    data class Row(val nodes: List<MathNode>) : MathNode()

    /** [limits]: `\limits` true / `\nolimits` false / unspecified null. */
    data class Scripts(val base: MathNode, val sub: MathNode?, val sup: MathNode?, val limits: Boolean?) : MathNode()

    /** [style]: 0 forces display (`\dfrac`), 1 forces text (`\tfrac`), null inherits. */
    data class Frac(val num: MathNode, val den: MathNode, val bar: Boolean, val style: Int?) : MathNode()

    data class Sqrt(val body: MathNode, val index: MathNode?) : MathNode()

    /** `\left<left> body \right<right>`; an empty delimiter string means `.` (none). */
    data class Delimited(val left: String, val right: String, val body: MathNode) : MathNode()

    /** A delimiter at a fixed size in em, from `\big`, `\Big`, `\bigg`, `\Bigg`. */
    data class Delim(val text: String, val heightEm: Float, val atom: Atom) : MathNode()

    data class Accent(val kind: AccentKind, val body: MathNode) : MathNode()

    /** Horizontal glue in math units (1mu = 1/18 em). */
    data class Space(val mu: Float) : MathNode()

    data class Table(
        val rows: List<List<MathNode>>,
        val aligns: String,
        val left: String,
        val right: String,
        val kind: TableKind,
    ) : MathNode()

    /** `\displaystyle` (0), `\textstyle` (1), `\scriptstyle` (2) applied to the rest of a row. */
    data class StyleSwitch(val level: Int, val body: MathNode) : MathNode()

    data class Classed(val atom: Atom, val body: MathNode) : MathNode()
}

internal data class Sym(
    val text: String,
    val atom: Atom,
    val variant: MathVariant = MathVariant.UPRIGHT,
    val limits: Boolean = false,
    val big: Boolean = false,
)

internal object MathSymbols {

    private fun ord(t: String) = Sym(t, Atom.ORD)
    private fun bin(t: String) = Sym(t, Atom.BIN)
    private fun rel(t: String) = Sym(t, Atom.REL)
    private fun greek(t: String) = Sym(t, Atom.ORD, MathVariant.ITALIC)

    private val GREEK: Map<String, Sym> = mapOf(
        "alpha" to greek("α"), "beta" to greek("β"), "gamma" to greek("γ"), "delta" to greek("δ"),
        "epsilon" to greek("ϵ"), "varepsilon" to greek("ε"), "zeta" to greek("ζ"), "eta" to greek("η"),
        "theta" to greek("θ"), "vartheta" to greek("ϑ"), "iota" to greek("ι"), "kappa" to greek("κ"),
        "varkappa" to greek("ϰ"), "lambda" to greek("λ"), "mu" to greek("μ"), "nu" to greek("ν"),
        "xi" to greek("ξ"), "omicron" to greek("ο"), "pi" to greek("π"), "varpi" to greek("ϖ"),
        "rho" to greek("ρ"), "varrho" to greek("ϱ"), "sigma" to greek("σ"), "varsigma" to greek("ς"),
        "tau" to greek("τ"), "upsilon" to greek("υ"), "phi" to greek("ϕ"), "varphi" to greek("φ"),
        "chi" to greek("χ"), "psi" to greek("ψ"), "omega" to greek("ω"),
        "Gamma" to ord("Γ"), "Delta" to ord("Δ"), "Theta" to ord("Θ"), "Lambda" to ord("Λ"),
        "Xi" to ord("Ξ"), "Pi" to ord("Π"), "Sigma" to ord("Σ"), "Upsilon" to ord("Υ"),
        "Phi" to ord("Φ"), "Psi" to ord("Ψ"), "Omega" to ord("Ω"),
    )

    private val BINARY: Map<String, Sym> = mapOf(
        "pm" to bin("±"), "mp" to bin("∓"), "times" to bin("×"), "div" to bin("÷"),
        "cdot" to bin("⋅"), "ast" to bin("∗"), "star" to bin("⋆"), "circ" to bin("∘"),
        "bullet" to bin("∙"), "cup" to bin("∪"), "cap" to bin("∩"), "setminus" to bin("∖"),
        "wedge" to bin("∧"), "land" to bin("∧"), "vee" to bin("∨"), "lor" to bin("∨"),
        "oplus" to bin("⊕"), "ominus" to bin("⊖"), "otimes" to bin("⊗"), "oslash" to bin("⊘"),
        "odot" to bin("⊙"), "uplus" to bin("⊎"), "sqcup" to bin("⊔"), "sqcap" to bin("⊓"),
        "dagger" to bin("†"), "ddagger" to bin("‡"), "diamond" to bin("⋄"), "amalg" to bin("⨿"),
        "triangleleft" to bin("◁"), "triangleright" to bin("▷"),
    )

    private val RELATION: Map<String, Sym> = mapOf(
        "leq" to rel("≤"), "le" to rel("≤"), "geq" to rel("≥"), "ge" to rel("≥"),
        "neq" to rel("≠"), "ne" to rel("≠"), "approx" to rel("≈"), "equiv" to rel("≡"),
        "sim" to rel("∼"), "simeq" to rel("≃"), "cong" to rel("≅"), "propto" to rel("∝"),
        "ll" to rel("≪"), "gg" to rel("≫"), "prec" to rel("≺"), "succ" to rel("≻"),
        "preceq" to rel("⪯"), "succeq" to rel("⪰"), "subset" to rel("⊂"), "supset" to rel("⊃"),
        "subseteq" to rel("⊆"), "supseteq" to rel("⊇"), "subsetneq" to rel("⊊"), "supsetneq" to rel("⊋"),
        "nsubseteq" to rel("⊈"), "in" to rel("∈"), "notin" to rel("∉"), "ni" to rel("∋"), "owns" to rel("∋"),
        "mid" to rel("∣"), "nmid" to rel("∤"), "parallel" to rel("∥"), "perp" to rel("⊥"),
        "vdash" to rel("⊢"), "dashv" to rel("⊣"), "models" to rel("⊨"),
        "to" to rel("→"), "rightarrow" to rel("→"), "leftarrow" to rel("←"), "gets" to rel("←"),
        "Rightarrow" to rel("⇒"), "Leftarrow" to rel("⇐"), "leftrightarrow" to rel("↔"),
        "Leftrightarrow" to rel("⇔"), "iff" to rel("⟺"), "implies" to rel("⟹"), "impliedby" to rel("⟸"),
        "mapsto" to rel("↦"), "longrightarrow" to rel("⟶"), "longleftarrow" to rel("⟵"),
        "longmapsto" to rel("⟼"), "Longrightarrow" to rel("⟹"), "Longleftarrow" to rel("⟸"),
        "Longleftrightarrow" to rel("⟺"), "uparrow" to rel("↑"), "downarrow" to rel("↓"),
        "updownarrow" to rel("↕"), "Uparrow" to rel("⇑"), "Downarrow" to rel("⇓"),
        "nearrow" to rel("↗"), "searrow" to rel("↘"), "swarrow" to rel("↙"), "nwarrow" to rel("↖"),
        "hookrightarrow" to rel("↪"), "hookleftarrow" to rel("↩"), "rightleftharpoons" to rel("⇌"),
        "asymp" to rel("≍"), "doteq" to rel("≐"), "coloneqq" to rel("≔"), "triangleq" to rel("≜"),
        "nless" to rel("≮"), "ngtr" to rel("≯"), "nleq" to rel("≰"), "ngeq" to rel("≱"),
        "smile" to rel("⌣"), "frown" to rel("⌢"), "sqsubseteq" to rel("⊑"), "sqsupseteq" to rel("⊒"),
        "leqslant" to rel("⩽"), "geqslant" to rel("⩾"), "lesssim" to rel("≲"), "gtrsim" to rel("≳"),
        "approxeq" to rel("≊"), "vDash" to rel("⊨"), "bowtie" to rel("⋈"),
    )

    private val ORDINARY: Map<String, Sym> = mapOf(
        "infty" to ord("∞"), "partial" to ord("∂"), "nabla" to ord("∇"), "emptyset" to ord("∅"),
        "varnothing" to ord("∅"), "forall" to ord("∀"), "exists" to ord("∃"), "nexists" to ord("∄"),
        "neg" to ord("¬"), "lnot" to ord("¬"), "top" to ord("⊤"), "bot" to ord("⊥"),
        "angle" to ord("∠"), "measuredangle" to ord("∡"), "hbar" to ord("ℏ"), "ell" to ord("ℓ"),
        "Re" to ord("ℜ"), "Im" to ord("ℑ"), "aleph" to ord("ℵ"), "wp" to ord("℘"),
        "prime" to ord("′"), "backprime" to ord("‵"), "degree" to ord("°"), "triangle" to ord("△"),
        "square" to ord("□"), "Box" to ord("□"), "Diamond" to ord("◇"), "clubsuit" to ord("♣"),
        "diamondsuit" to ord("♢"), "heartsuit" to ord("♡"), "spadesuit" to ord("♠"),
        "flat" to ord("♭"), "natural" to ord("♮"), "sharp" to ord("♯"), "checkmark" to ord("✓"),
        "ldots" to ord("…"), "dots" to ord("…"), "dotsc" to ord("…"), "cdots" to ord("⋯"),
        "dotsb" to ord("⋯"), "dotsm" to ord("⋯"), "dotsi" to ord("⋯"), "vdots" to ord("⋮"), "ddots" to ord("⋱"),
        "imath" to Sym("ı", Atom.ORD, MathVariant.ITALIC), "jmath" to Sym("ȷ", Atom.ORD, MathVariant.ITALIC),
        "S" to ord("§"), "P" to ord("¶"), "copyright" to ord("©"), "pounds" to ord("£"),
        "backslash" to ord("\\"), "vert" to ord("|"), "Vert" to ord("‖"),
        "%" to ord("%"), "$" to ord("$"), "&" to ord("&"), "#" to ord("#"), "_" to ord("_"),
        "|" to ord("‖"), "colon" to Sym(":", Atom.PUNCT), "ldotp" to Sym(".", Atom.PUNCT),
        "{" to Sym("{", Atom.OPEN), "}" to Sym("}", Atom.CLOSE),
        "lbrace" to Sym("{", Atom.OPEN), "rbrace" to Sym("}", Atom.CLOSE),
        "lbrack" to Sym("[", Atom.OPEN), "rbrack" to Sym("]", Atom.CLOSE),
        "langle" to Sym("⟨", Atom.OPEN), "rangle" to Sym("⟩", Atom.CLOSE),
        "lfloor" to Sym("⌊", Atom.OPEN), "rfloor" to Sym("⌋", Atom.CLOSE),
        "lceil" to Sym("⌈", Atom.OPEN), "rceil" to Sym("⌉", Atom.CLOSE),
        "lvert" to Sym("|", Atom.OPEN), "rvert" to Sym("|", Atom.CLOSE),
        "lVert" to Sym("‖", Atom.OPEN), "rVert" to Sym("‖", Atom.CLOSE),
    )

    /** Large operators: `\sum` family set limits above/below in display style. */
    private val BIG_OPERATORS: Map<String, Sym> = mapOf(
        "sum" to Sym("∑", Atom.OP, limits = true, big = true),
        "prod" to Sym("∏", Atom.OP, limits = true, big = true),
        "coprod" to Sym("∐", Atom.OP, limits = true, big = true),
        "bigcup" to Sym("⋃", Atom.OP, limits = true, big = true),
        "bigcap" to Sym("⋂", Atom.OP, limits = true, big = true),
        "bigvee" to Sym("⋁", Atom.OP, limits = true, big = true),
        "bigwedge" to Sym("⋀", Atom.OP, limits = true, big = true),
        "bigoplus" to Sym("⨁", Atom.OP, limits = true, big = true),
        "bigotimes" to Sym("⨂", Atom.OP, limits = true, big = true),
        "bigodot" to Sym("⨀", Atom.OP, limits = true, big = true),
        "bigsqcup" to Sym("⨆", Atom.OP, limits = true, big = true),
        "biguplus" to Sym("⨄", Atom.OP, limits = true, big = true),
        "int" to Sym("∫", Atom.OP, big = true),
        "iint" to Sym("∬", Atom.OP, big = true),
        "iiint" to Sym("∭", Atom.OP, big = true),
        "oint" to Sym("∮", Atom.OP, big = true),
        "oiint" to Sym("∯", Atom.OP, big = true),
    )

    /** Named operators set upright (`\sin`, `\log`, …). Value: whether limits go below in display style. */
    private val FUNCTIONS: Map<String, Boolean> = mapOf(
        "arccos" to false, "arcsin" to false, "arctan" to false, "arg" to false, "cos" to false,
        "cosh" to false, "cot" to false, "coth" to false, "csc" to false, "deg" to false,
        "det" to true, "dim" to false, "exp" to false, "gcd" to true, "hom" to false,
        "inf" to true, "ker" to false, "lg" to false, "lim" to true, "liminf" to true,
        "limsup" to true, "ln" to false, "log" to false, "max" to true, "min" to true,
        "Pr" to true, "sec" to false, "sin" to false, "sinh" to false, "sup" to true,
        "tan" to false, "tanh" to false,
    )

    private val OPERATOR_DISPLAY = mapOf("liminf" to "lim inf", "limsup" to "lim sup")

    fun lookup(name: String): Sym? {
        GREEK[name]?.let { return it }
        BIG_OPERATORS[name]?.let { return it }
        BINARY[name]?.let { return it }
        RELATION[name]?.let { return it }
        ORDINARY[name]?.let { return it }
        FUNCTIONS[name]?.let { limits ->
            return Sym(OPERATOR_DISPLAY[name] ?: name, Atom.OP, limits = limits)
        }
        return null
    }

    /** Glyph for a character typed directly (not via a control sequence) that has a non-ordinary class. */
    private val UNICODE_CLASSES: Map<Char, Atom> = buildMap {
        for (c in "≤≥≠≈≡∼≃≅∝≪≫≺≻⊂⊃⊆⊇∈∉∋∣∥⊥⊢⊣⊨→←⇒⇐↔⇔↦⟶⟵⟹⟸⟺↑↓↕⇑⇓↗↘↙↖⇌≍≐≔≜") put(c, Atom.REL)
        for (c in "±∓×÷⋅∗⋆∘∙∪∩∖∧∨⊕⊖⊗⊘⊙⊎⊔⊓†‡⋄−") put(c, Atom.BIN)
        for (c in "⟨⌊⌈") put(c, Atom.OPEN)
        for (c in "⟩⌋⌉") put(c, Atom.CLOSE)
        for (c in "∑∏∫∮") put(c, Atom.OP)
    }

    fun unicodeAtom(c: Char): Atom = UNICODE_CLASSES[c] ?: Atom.ORD

    /** `\mathbb` letters, via the Letterlike Symbols block where it has them, else Mathematical Alphanumerics. */
    private val BLACKBOARD_SPECIAL = mapOf(
        'C' to "ℂ", 'H' to "ℍ", 'N' to "ℕ", 'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ", 'Z' to "ℤ",
    )
    private val SCRIPT_SPECIAL = mapOf(
        'B' to "ℬ", 'E' to "ℰ", 'F' to "ℱ", 'H' to "ℋ", 'I' to "ℐ", 'L' to "ℒ", 'M' to "ℳ", 'R' to "ℛ",
        'e' to "ℯ", 'g' to "ℊ", 'o' to "ℴ",
    )
    private val FRAKTUR_SPECIAL = mapOf('C' to "ℭ", 'H' to "ℌ", 'I' to "ℑ", 'R' to "ℜ", 'Z' to "ℨ")

    private fun alphanumeric(c: Char, upperBase: Int, lowerBase: Int, digitBase: Int?, special: Map<Char, String>): String? {
        special[c]?.let { return it }
        return when (c) {
            in 'A'..'Z' -> String(Character.toChars(upperBase + (c - 'A')))
            in 'a'..'z' -> if (lowerBase > 0) String(Character.toChars(lowerBase + (c - 'a'))) else null
            in '0'..'9' -> if (digitBase != null) String(Character.toChars(digitBase + (c - '0'))) else null
            else -> null
        }
    }

    fun blackboard(c: Char): String? = alphanumeric(c, 0x1D538, 0x1D552, 0x1D7D8, BLACKBOARD_SPECIAL)
    fun script(c: Char): String? = alphanumeric(c, 0x1D49C, 0x1D4B6, null, SCRIPT_SPECIAL)
    fun fraktur(c: Char): String? = alphanumeric(c, 0x1D504, 0x1D51E, null, FRAKTUR_SPECIAL)

    /** Delimiter written after `\left`, `\right`, `\big`…, normalised: `\{` -> "{", `\langle` -> "⟨", `.` -> "". */
    fun delimiterFromControl(name: String): String? = when (name) {
        "{", "lbrace" -> "{"
        "}", "rbrace" -> "}"
        "|", "Vert", "lVert", "rVert" -> "‖"
        "vert", "lvert", "rvert" -> "|"
        "langle" -> "⟨"
        "rangle" -> "⟩"
        "lfloor" -> "⌊"
        "rfloor" -> "⌋"
        "lceil" -> "⌈"
        "rceil" -> "⌉"
        "backslash" -> "\\"
        "lbrack" -> "["
        "rbrack" -> "]"
        "uparrow" -> "↑"
        "downarrow" -> "↓"
        else -> null
    }

    fun isOpeningDelimiter(d: String): Boolean = d in setOf("(", "[", "{", "⟨", "⌊", "⌈")
}
