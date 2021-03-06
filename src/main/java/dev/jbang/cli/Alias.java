package dev.jbang.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import dev.jbang.Settings;
import dev.jbang.Util;

import picocli.CommandLine;

@CommandLine.Command(name = "alias", description = "Manage aliases.")
public class Alias {

	@CommandLine.Spec
	CommandLine.Model.CommandSpec spec;

	@CommandLine.Command(name = "add", description = "Add alias for script reference.")
	public Integer add(
			@CommandLine.Option(names = { "--description",
					"-d" }, description = "A description for the alias") String description,
			@CommandLine.Option(names = { "-D" }, description = "set a system property") Map<String, String> properties,
			@CommandLine.Parameters(paramLabel = "name", index = "0", description = "A name for the alias", arity = "1") String name,
			@CommandLine.Parameters(paramLabel = "scriptOrFile", index = "1", description = "A file or URL to a Java code file", arity = "1") String scriptOrFile,
			@CommandLine.Parameters(paramLabel = "params", index = "2..*", arity = "0..*", description = "Parameters to pass on to the script") List<String> userParams) {
		if (!name.matches("^[a-zA-Z][-\\w]*$")) {
			throw new IllegalArgumentException(
					"Invalid alias name, it should start with a letter followed by 0 or more letters, digits, underscores or hyphens");
		}
		Settings.addAlias(name, scriptOrFile, description, userParams, properties);
		return CommandLine.ExitCode.SOFTWARE;
	}

	@CommandLine.Command(name = "list", description = "Lists locally defined aliases or from the given catalog.")
	public Integer list(
			@CommandLine.Parameters(paramLabel = "catalogName", index = "0", description = "The name of a catalog", arity = "0..1") String catalogName) {
		PrintWriter out = spec.commandLine().getOut();
		Settings.Aliases aliases = Util.getCatalogAliasesByName(catalogName, false);
		printAliases(out, catalogName, aliases);
		return CommandLine.ExitCode.SOFTWARE;
	}

	static void printAliases(PrintWriter out, String catalogName, Settings.Aliases aliases) {
		aliases.aliases
						.keySet()
						.stream()
						.sorted()
						.forEach(name -> {
							try {
								Settings.Alias ai = Util.getCatalogAlias(aliases, name);
								String fullName = catalogName != null ? catalogName + "@" + name : name;
								if (ai.description != null) {
									out.println(fullName + " = " + ai.description);
									out.println(Util.repeat(" ", fullName.length()) + "   (" + ai.scriptRef + ")");
								} else {
									out.println(fullName + " = " + ai.scriptRef);
								}
								if (ai.arguments != null) {
									out.println(
											Util.repeat(" ", fullName.length()) + "   Arguments: "
													+ String.join(" ", ai.arguments));
								}
								if (ai.properties != null) {
									out.println(
											Util.repeat(" ", fullName.length()) + "   Properties: " + ai.properties);
								}
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
						});
	}

	@CommandLine.Command(name = "remove", description = "Remove existing alias.")
	public Integer remove(
			@CommandLine.Parameters(paramLabel = "name", index = "0", description = "The name of the alias", arity = "1") String name) {
		Settings.removeAlias(name);
		return CommandLine.ExitCode.SOFTWARE;
	}
}
