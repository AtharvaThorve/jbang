package dev.jbang;

import picocli.CommandLine;

public class VersionProvider implements CommandLine.IVersionProvider, CommandLine.IExitCodeExceptionMapper {
	@Override
	public String[] getVersion() throws Exception {
		return new String[] { dev.jbang.BuildConfig.VERSION };
	}

	@Override
	public int getExitCode(Throwable t) {
		if (t instanceof ExitException) {
			return ((ExitException) t).getStatus();
		} else if (t instanceof CommandLine.ParameterException) {
			return CommandLine.ExitCode.USAGE;
		}
		return CommandLine.ExitCode.SOFTWARE;
	}
}
