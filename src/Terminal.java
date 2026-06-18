/// # `Terminal.java` (TerminalTools)
///
/// A lightweight, user-friendly ANSI terminal wrapper that (mostly) works on all platforms.
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
/// - `format`: Formats a string and returns it.
/// - `format256`: Formats a string using `xterm-256color` as an integer, for both text/background.
/// - `formatRGB`: Formats a string using individual integers to RGB, for both text/background.
/// - `print`/`println`: Outputs formatted text to the terminal.
/// - `set`/`restore`: Changes persistent styling attributes.
public class Terminal {

	/// ### `Colors`
	/// Standard ANSI color codes and macOS system RGB colors as `String`s
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
	/// ANSI and macOS system RGB codes for background colors as `String`s.
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

	private static final String esc = "\u001B";

	private static String color256(int code) {return "38;5;" + code;}
	private static String bg256(int code) {return "48;5;" + code;}
	private static String colorRGB(int r, int g, int b) {return "38;2;" + r + ";" + g + ";" + b;}
	private static String bgRGB(int r, int g, int b) {return "48;2;" + r + ";" + g + ";" + b;}

	private static String build(String content, String... codes) {
		return (esc + "[" + String.join(";", codes) + "m" + content + esc + "[0m").translateEscapes();
	}

	private static String buildSet(String... codes) {
		return (esc + "[" + String.join(";", codes) + "m").translateEscapes();
	}

	// Formatting Methods (Returning Strings)
	String format(String s, Text t, Colors c) {return build(s, t.getCode(), c.getCode());}
	String format(String s, Text t, Colors c, Background b) {return build(s, t.getCode(), c.getCode(), b.getCode());}
	String format(String s, Text t) {return build(s, t.getCode());}
	String format(String s, Colors c) {return build(s, c.getCode());}
	String format(String s, Background b) {return build(s, b.getCode());}
	String format(String s, Colors c, Background b) {return build(s, c.getCode(), b.getCode());}

	String format256(String s, int f256) {return build(s, color256(f256));}
	String format256(String s, int f256, int b256) {return build(s, color256(f256), bg256(b256));}
	String format256(String s, Text t, int f256) {return build(s, t.getCode(), color256(f256));}
	String format256(String s, Text t, int f256, int b256) {return build(s, t.getCode(), color256(f256), bg256(b256));}

	String formatRGB(String s, int r, int g, int b) {return build(s, colorRGB(r, g, b));}
	String formatRGB(String s, int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {return build(s, colorRGB(fgR, fgG, fgB), bgRGB(bgR, bgG, bgB));}
	String formatRGB(String s, Text t, int r, int g, int b) {return build(s, t.getCode(), colorRGB(r, g, b));}
	String formatRGB(String s, Text t, int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {return build(s, t.getCode(), colorRGB(fgR, fgG, fgB), bgRGB(bgR, bgG, bgB));}

	// Printing Methods
	void print(String s) {IO.print(s);}
	void println() {IO.println();}
	void println(String s) {IO.println(s);}

	void print(String s, Text t, Colors c) {IO.print(format(s, t, c));}
	void print(String s, Text t, Colors c, Background b) {IO.print(format(s, t, c, b));}
	void print(String s, Text t) {IO.print(format(s, t));}
	void print(String s, Colors c) {IO.print(format(s, c));}
	void print(String s, Background b) {IO.print(format(s, b));}
	void print(String s, Colors c, Background b) {IO.print(format(s, c, b));}

	void println(String s, Text t, Colors c) {IO.println(format(s, t, c));}
	void println(String s, Text t, Colors c, Background b) {IO.println(format(s, t, c, b));}
	void println(String s, Text t) {IO.println(format(s, t));}
	void println(String s, Colors c) {IO.println(format(s, c));}
	void println(String s, Background b) {IO.println(format(s, b));}
	void println(String s, Colors c, Background b) {IO.println(format(s, c, b));}

	void print256(String s, int f256) {IO.print(format256(s, f256));}
	void print256(String s, int f256, int b256) {IO.print(format256(s, f256, b256));}
	void print256(String s, Text t, int f256) {IO.print(format256(s, t, f256));}
	void print256(String s, Text t, int f256, int b256) {IO.print(format256(s, t, f256, b256));}

	void println256(String s, int f256) {IO.println(format256(s, f256));}
	void println256(String s, int f256, int b256) {IO.println(format256(s, f256, b256));}
	void println256(String s, Text t, int f256) {IO.println(format256(s, t, f256));}
	void println256(String s, Text t, int f256, int b256) {IO.println(format256(s, t, f256, b256));}

	void printRGB(String s, int r, int g, int b) {IO.print(formatRGB(s, r, g, b));}
	void printRGB(String s, int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {IO.print(formatRGB(s, fgR, fgG, fgB, bgR, bgG, bgB));}
	void printRGB(String s, Text t, int r, int g, int b) {IO.print(formatRGB(s, t, r, g, b));}
	void printRGB(String s, Text t, int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {IO.print(formatRGB(s, t, fgR, fgG, fgB, bgR, bgG, bgB));}

	void printlnRGB(String s, int r, int g, int b) {IO.println(formatRGB(s, r, g, b));}
	void printlnRGB(String s, int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {IO.println(formatRGB(s, fgR, fgG, fgB, bgR, bgG, bgB));}
	void printlnRGB(String s, Text t, int r, int g, int b) {IO.println(formatRGB(s, t, r, g, b));}
	void printlnRGB(String s, Text t, int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {IO.println(formatRGB(s, t, fgR, fgG, fgB, bgR, bgG, bgB));}

	// State-setting Methods
	void set(Text t) {IO.print(buildSet(t.getCode()));}
	void set(Colors c) {IO.print(buildSet(c.getCode()));}
	void set(Background b) {IO.print(buildSet(b.getCode()));}
	void set(Text t, Colors c) {IO.print(buildSet(t.getCode(), c.getCode()));}
	void set(Text t, Colors c, Background b) {IO.print(buildSet(t.getCode(), c.getCode(), b.getCode()));}
	void set(Colors c, Background b) {IO.print(buildSet(c.getCode(), b.getCode()));}

	void set256(int f256) {IO.print(buildSet(color256(f256)));}
	void set256(int f256, int b256) {IO.print(buildSet(color256(f256), bg256(b256)));}
	void set256(Text t, int f256) {IO.print(buildSet(t.getCode(), color256(f256)));}
	void set256(Text t, int f256, int b256) {IO.print(buildSet(t.getCode(), color256(f256), bg256(b256)));}

	void setRGB(int r, int g, int b) {IO.print(buildSet(colorRGB(r, g, b)));}
	void setRGB(int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {IO.print(buildSet(colorRGB(fgR, fgG, fgB), bgRGB(bgR, bgG, bgB)));}
	void setRGB(Text t, int r, int g, int b) {IO.print(buildSet(t.getCode(), colorRGB(r, g, b)));}
	void setRGB(Text t, int fgR, int fgG, int fgB, int bgR, int bgG, int bgB) {IO.print(buildSet(t.getCode(), colorRGB(fgR, fgG, fgB), bgRGB(bgR, bgG, bgB)));}

	/// Restores the terminal formatting back to the system default configuration.
	public void restore() {
		IO.print((esc + "[0m").translateEscapes());
	}

	// error template
	void error(String error){
		print256(" error ", Text.bold, 255, 160);
		print(" " + error + "\n", Text.italic, Colors.red);
	}
	void error(String error, String method){
		print256(" error ", Text.bold, 255, 160);
		print(" - " + method + ": ", Text.bold, Colors.brightRed);
		print(error + "\n", Text.italic, Colors.red);
	}
	void error(String error, String method, int num){
		print256(" error ", Text.bold, 255, 160);
		print(" - " + method + "[" + num +"]: ", Text.bold, Colors.brightRed);
		print(error + "\n", Text.italic, Colors.red);
	}

	// success template
	void success(String success){
		print256(" success ", Text.bold, 255, 71);
		print(" " + success + "\n", Text.italic, Colors.green);
	}
	void success(String success, String method){
		print256(" success ", Text.bold, 255, 71);
		print(" - " + method + ": ", Text.bold, Colors.brightGreen);
		print(success + "\n", Text.italic, Colors.green);
	}
	void success(String success, String method, int num){
		print256(" success ", Text.bold, 255, 71);
		print(" - " + method + "[" + num +"]: ", Text.bold, Colors.brightGreen);
		print(success + "\n", Text.italic, Colors.green);
	}

	// warning template
	void warn(String warning){
		print256(" warning ", Text.bold, 0, 214);
		print(" " + warning + "\n", Text.italic, Colors.yellow);
	}
	void warn(String warning, String method){
		print256(" warning ", Text.bold, 0, 214);
		print(" - " + method + ": ", Text.bold, Colors.brightYellow);
		print(warning + "\n", Text.italic, Colors.yellow);
	}
	void warn(String warning, String method, int num){
		print256(" warning ", Text.bold, 0, 214);
		print(" - " + method + "[" + num +"]: ", Text.bold, Colors.brightYellow);
		print(warning + "\n", Text.italic, Colors.yellow);
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