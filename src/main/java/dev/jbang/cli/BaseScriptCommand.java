package dev.jbang.cli;

import static dev.jbang.Util.swizzleURL;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import dev.jbang.DependencyUtil;
import dev.jbang.ExitException;
import dev.jbang.Script;
import dev.jbang.Settings;
import dev.jbang.Util;

import picocli.CommandLine;

public abstract class BaseScriptCommand extends BaseCommand {

	@CommandLine.Option(names = { "-o",
			"--offline" }, description = "Work offline. Fail-fast if dependencies are missing.")
	boolean offline;

	@CommandLine.Option(names = {
			"--insecure" }, description = "Enable insecure trust of all SSL certificates.", defaultValue = "false")
	boolean insecure;

	@CommandLine.Parameters(index = "0", arity = "1", description = "A file with java code or if named .jsh will be run with jshell")
	String scriptOrFile;

	@CommandLine.Parameters(index = "1..*", arity = "0..*", description = "Parameters to pass on to the script")
	List<String> userParams = new ArrayList<>();

	protected Script script;

	protected void enableInsecure() {
		try {
			// Create a trust manager that does not validate certificate chains
			TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
				public X509Certificate[] getAcceptedIssuers() {
					return null;
				}

				public void checkClientTrusted(X509Certificate[] certs, String authType) {
				}

				public void checkServerTrusted(X509Certificate[] certs, String authType) {
				}
			}
			};

			// Install the all-trusting trust manager
			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(null, trustAllCerts, new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

			// Create all-trusting host name verifier
			HostnameVerifier allHostsValid = new HostnameVerifier() {
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			};

			// Install the all-trusting host verifier
			HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
		} catch (NoSuchAlgorithmException | KeyManagementException ex) {
			throw new RuntimeException(ex);
		}
	}

	public static Script prepareScript(String scriptResource, List<String> arguments, Map<String, String> properties)
			throws IOException {
		File scriptFile = getScriptFile(scriptResource);
		if (scriptFile == null) {
			// Not found as such, so let's check the aliases
			Settings.Alias alias = Util.getAlias(scriptResource, arguments, properties);
			if (alias != null) {
				scriptFile = getScriptFile(alias.scriptRef);
				arguments = alias.arguments;
				properties = alias.properties;
			}
		}

		// Support URLs as script files
		/*
		 * if(scriptResource.startsWith("http://")||scriptResource.startsWith("https://"
		 * )) { scriptFile = fetchFromURL(scriptResource)
		 *
		 * includeContext = URI(scriptResource.run { substring(lastIndexOf('/') + 1) })
		 * }
		 *
		 * // Support for support process substitution and direct script arguments
		 * if(scriptFile==null&&!scriptResource.endsWith(".kts")&&!scriptResource.
		 * endsWith(".kt")) { val scriptText = if (File(scriptResource).canRead()) {
		 * File(scriptResource).readText().trim() } else { // the last resort is to
		 * assume the input to be a java program scriptResource.trim() }
		 *
		 * scriptFile = createTmpScript(scriptText) }
		 */
		// just proceed if the script file is a regular file at this point
		if (scriptFile == null || !scriptFile.canRead()) {
			throw new IllegalArgumentException("Could not read script argument " + scriptResource);
		}

		// note script file must be not null at this point

		Script s = null;
		try {
			s = new Script(scriptFile, arguments, properties);
			s.setOriginal(scriptResource);
		} catch (FileNotFoundException e) {
			throw new ExitException(1, e);
		}
		return s;
	}

	private static File getScriptFile(String scriptResource) throws IOException {
		File scriptFile;

		// we need to keep track of the scripts dir or the working dir in case of stdin
		// script to correctly resolve includes
		// var includeContext = new File(".").toURI();

		// map script argument to script file
		File probe = new File(scriptResource);

		if (!probe.canRead()) {
			// not a file so let's keep the script-file undefined here
			scriptFile = null;
		} else if (probe.getName().endsWith(".jar") || probe.getName().endsWith(".java")
				|| probe.getName().endsWith(".jsh")) {
			scriptFile = probe;
		} else {
			String original = Util.readString(probe.toPath());
			// TODO: move temp handling somewhere central
			String urlHash = Util.getStableID(original);

			if (original.startsWith("#!")) { // strip bash !# if exists
				original = original.substring(original.indexOf("\n"));
			}

			File tempFile = Settings.getCacheDir()
									.resolve(
											"script_cache_" + urlHash + "/" + unkebabify(probe.getName()))
									.toFile();
			tempFile.getParentFile().mkdirs();
			Util.writeString(tempFile.toPath().toAbsolutePath(), original);
			scriptFile = tempFile;

			// if we can "just" read from script resource create tmp file
			// i.e. script input is process substitution file handle
			// not FileInputStream(this).bufferedReader().use{ readText()} does not work nor
			// does this.readText
			// includeContext = this.absoluteFile.parentFile.toURI()
			// createTmpScript(FileInputStream(this).bufferedReader().readText())
		}

		// support stdin
		if (scriptResource.equals("-") || scriptResource.equals("/dev/stdin")) {
			String scriptText = new BufferedReader(
					new InputStreamReader(System.in, StandardCharsets.UTF_8))
																				.lines()
																				.collect(Collectors.joining(
																						System.lineSeparator()));

			String urlHash = Util.getStableID(scriptText);
			File cache = Settings.getCacheDir().resolve("stdin_cache_" + urlHash).toFile();
			cache.mkdirs();
			scriptFile = new File(cache, urlHash + ".jsh");
			Util.writeString(scriptFile.toPath(), scriptText);
		} else if (scriptResource.startsWith("http://") || scriptResource.startsWith("https://")
				|| scriptResource.startsWith("file:/")) {
			// support url's as script files
			scriptFile = fetchFromURL(scriptResource);
		} else if (DependencyUtil.looksLikeAGav(scriptResource.toString())) {
			// todo honor offline
			String gav = scriptResource.toString();
			String s = new DependencyUtil().resolveDependencies(Arrays.asList(gav),
					Collections.emptyList(), false, true, false);
			scriptFile = new File(s);
		}

		return scriptFile;
	}

	/**
	 *
	 * @param name script name
	 * @return camel case of kebab string if name does not end with .java or .jsh
	 */
	static String unkebabify(String name) {
		if (!(name.endsWith(".java") || name.endsWith(".jsh"))) {
			name = kebab2camel(name) + ".java";
		}
		return name;
	}

	static String kebab2camel(String name) {

		if (name.contains("-")) { // xyz-plug becomes XyzPlug
			return Arrays	.stream(name.split("\\-"))
							.map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase())
							.collect(Collectors.joining());
		} else {
			return name; // xyz stays xyz
		}
	}

	private static File fetchFromURL(String scriptURL) {
		try {
			java.net.URI uri = new java.net.URI(scriptURL);

			if (!Settings.getTrustedSources().isURLTrusted(uri)) {
				throw new ExitException(10, scriptURL + " is not from a trusted source thus aborting.\n" +
						"If you trust the url to be safe to run are here a few suggestions:\n" +
						"Limited trust:\n    jbang trust add " + goodTrustURL(scriptURL) + "\n" +
						"Trust all subdomains:\n    jbang trust add " + "*." + uri.getAuthority() + "\n" +
						"Trust all sources (WARNING! disables url protection):\n    jbang trust add \"*\"" + "\n" +
						"\nFor more control edit ~/.jbang/trusted-sources.json" + "\n");
			}

			scriptURL = swizzleURL(scriptURL);

			String urlHash = Util.getStableID(scriptURL);
			File urlCache = Settings.getCacheDir().resolve("url_cache_" + urlHash).toFile();
			urlCache.mkdirs();
			Path path = Util.downloadFileSwizzled(scriptURL, urlCache);

			return path.toFile();
		} catch (IOException | URISyntaxException e) {
			throw new ExitException(2, "Could not download " + scriptURL, e);
		}
	}

	private static String goodTrustURL(String url) {
		String originalUrl = url;

		url = url.replaceFirst("^https://github.com/(.*)/blob/(.*)$",
				"https://github.com/$1/");

		url = url.replaceFirst("^https://gitlab.com/(.*)/-/blob/(.*)$",
				"https://gitlab.com/$1/");

		url = url.replaceFirst("^https://bitbucket.org/(.*)/src/(.*)$",
				"https://bitbucket.org/$1/");

		url = url.replaceFirst("^https://twitter.com/(.*)/status/(.*)$",
				"https://twitter.com/$1/");

		if (url.equals(originalUrl)) {
			java.net.URI u = null;
			try {
				u = new java.net.URI(url);
			} catch (URISyntaxException e) {
				return url;
			}
			url = u.getScheme() + "://" + u.getAuthority();
		}

		return url;
	}

	public static String getBaseName(String fileName) {
		int index = fileName.lastIndexOf('.');
		if (index == -1) {
			return kebab2camel(fileName);
		} else {
			return fileName.substring(0, index);
		}
	}

}
