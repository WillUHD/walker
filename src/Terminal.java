/// # `Terminal.java` (TerminalTools)
///
/// A lightweight, user-friendly ANSI terminal wrapper that (mostly) works on all platforms.
/// > [!NOTE] - Currently, **public methods are `void` and they only print to IO**.
///
/// ### Design
/// - Uses the JDK15 `.translateEscapes()` to properly escape instead of printing a literal
/// - Common **predefined color/background/effect enums** so it's easy to format text
/// - **Templates** for success, warnings, error (green, yellow, red)
/// - Future direction:
///     - more terminal interaction (backspaces, etc)
///     - accept custom escapes (instead of just the predefined enums)
///     - return formatted text (instead of just printing)
///     - expand templates(?)
///
/// ### Use this class
/// - **Instantiate**: `var t = new Terminal();` and call things like `t.format();`
/// - `format`: Formats a string and prints it to the terminal.
/// - `format256`: Formats a string using `xterm-256color` as an integer, for both text/background
/// - `formatRGB`: Formats a string using individual integers to RGB, for both text/background
public class Terminal {

	/// ### `Colors`
	/// Standard ANSI color codes as `String`s
	enum Colors {
		black("30"),
		red("31"),
		green("32"),
		yellow("33"),
		blue("34"),
		magenta("35"),
		cyan("36"),
		white("37"),
		brightBlack("90"),
		brightRed("91"),
		brightGreen("92"),
		brightYellow("93"),
		brightBlue("94"),
		brightMagenta("95"),
		brightCyan("96"),
		brightWhite("97");

		private final String code;
		Colors(String code){this.code = code;}
		public String getCode(){return code;}
	}

	/// ### `Background`
	/// ANSI color codes for background colors as `String`s. Slightly different from `Colors`.
	enum Background {
		black("40"),
		red("41"),
		green("42"),
		yellow("43"),
		blue("44"),
		magenta("45"),
		cyan("46"),
		white("47"),
		brightBlack("100"),
		brightRed("101"),
		brightGreen("102"),
		brightYellow("103"),
		brightBlue("104"),
		brightMagenta("105"),
		brightCyan("106"),
		brightWhite("107");

		private final String code;
		Background(String code){this.code = code;}
		public String getCode(){return code;}
	}

	/// ### `Text`
	/// ANSI codes for text effects (such as bold, italics, etc)
	enum Text {
		normal("0"),
		bold("1"),
		dim("2"),
		italic("3"),
		underline("4"),
		blink("5"),
		reverse("7"),
		hidden("8"),
		strikethrough("9");

		private final String code;
		Text(String code){this.code = code;}
		public String getCode(){return code;}
	}

	private static final String esc = "\033";

	private static String color256(int code) {return "38;5;" + code;}
	private static String bg256(int code) {return "48;5;" + code;}
	private static String colorRGB(int r, int g, int b) {return "38;2;" + r + ";" + g + ";" + b;}
	private static String bgRGB(int r, int g, int b) {return "48;2;" + r + ";" + g + ";" + b;}

	// put content first because java problem
	private static String build(String content, String... codes) {
		return (esc + "[" + String.join(";", codes) + "m" + content + esc + "[0m").translateEscapes();
	}

	void format(String s, Text t, Colors c) {IO.print(build(s, t.getCode(), c.getCode()));}
	void format(String s, Text t, Colors c, Background b) {IO.print(build(s, t.getCode(), c.getCode(), b.getCode()));}
	void format(String s, Text t) {IO.print(build(s, t.getCode()));}
	void format(String s, Colors c) {IO.print(build(s, c.getCode()));}
	void format(String s, Background b) {IO.print(build(s, b.getCode()));}
	void format(String s, Colors c, Background b) {IO.print(build(s, c.getCode(), b.getCode()));}

	void format256(String s, int f256) {IO.print(build(s, color256(f256)));}
	void format256(String s, int f256, int b256) {IO.print(build(s, color256(f256), bg256(b256)));}
	void format256(String s, Text t, int f256) {IO.print(build(s, t.getCode(), color256(f256)));}
	void format256(String s, Text t, int f256, int b256) {IO.print(build(s, t.getCode(), color256(f256), bg256(b256)));}

	void formatRGB(String s, int r, int g, int b) {IO.print(build(s, colorRGB(r, g, b)));}
	void formatRGB(String s, int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {IO.print(build(s, colorRGB(fgR, fgG, fgB), bgRGB(bgR, bgG, bgB)));}
	void formatRGB(String s, Text t, int r, int g, int b) {IO.print(build(s, t.getCode(), colorRGB(r, g, b)));}
	void formatRGB(String s, Text t, int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {IO.print(build(s, t.getCode(), colorRGB(fgR, fgG, fgB), bgRGB(bgR, bgG, bgB)));}

	// error template
	void error(String error){
		format256(" error ", Text.bold,255, 160);
		format(" " + error + "\n", Text.italic, Colors.red);
	}
	void error(String error, String method){
		format256(" error ", Text.bold,255, 160);
		format(" - " + method + ": ", Text.bold, Colors.brightRed);
		format(error + "\n", Text.italic, Colors.red);
	}
	void error(String error, String method, int num){
		format256(" error ", Text.bold,255, 160);
		format(" - " + method + "[" + num +"]: ", Text.bold, Colors.brightRed);
		format(error + "\n", Text.italic, Colors.red);
	}

	// success template
	void success(String success){
		format256(" success ", Text.bold,255, 71);
		format(" " + success + "\n", Text.italic, Colors.green);
	}
	void success(String success, String method){
		format256(" success ", Text.bold,255, 71);
		format(" - " + method + ": ", Text.bold, Colors.brightGreen);
		format(success + "\n", Text.italic, Colors.green);
	}
	void success(String success, String method, int num){
		format256(" success ", Text.bold,255, 71);
		format(" - " + method + "[" + num +"]: ", Text.bold, Colors.brightGreen);
		format(success + "\n", Text.italic, Colors.green);
	}

	// warning template
	void warn(String warning){
		format256(" warning ", Text.bold,0, 214);
		format(" " + warning + "\n", Text.italic, Colors.yellow);
	}
	void warn(String warning, String method){
		format256(" warning ", Text.bold,0, 214);
		format(" - " + method + ": ", Text.bold, Colors.brightYellow);
		format(warning + "\n", Text.italic, Colors.yellow);
	}
	void warn(String warning, String method, int num){
		format256(" warning ", Text.bold,0, 214);
		format(" - " + method + "[" + num +"]: ", Text.bold, Colors.brightYellow);
		format(warning + "\n", Text.italic, Colors.yellow);
	}

	// interaction stuff
	void clearScreen(int mode) {IO.print((esc + "[" + mode + "J").translateEscapes());} // 0-3
	void clearLine(int mode) {IO.print((esc + "[" + mode + "K").translateEscapes());} // 0-2
	void setWindowTitle(String title) {IO.print((esc + "]0;" + title + "\007").translateEscapes());}

	// cursor stuff
	void cursorTo(int row, int col) {IO.print((esc + "[" + row + ";" + col + "H").translateEscapes());}
	void cursorUp(int n) {IO.print((esc + "[" + n + "A").translateEscapes());}
	void cursorDown(int n) {IO.print((esc + "[" + n + "B").translateEscapes());}
	void cursorRight(int n) {IO.print((esc + "[" + n + "C").translateEscapes());}
	void cursorLeft(int n) {IO.print((esc + "[" + n + "D").translateEscapes());}
	void showCursor() {IO.print((esc + "[?25h").translateEscapes());}
	void hideCursor() {IO.print((esc + "[?25l").translateEscapes());}
}
