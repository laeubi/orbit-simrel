/**
 * Copyright (c) 2023 Eclipse contributors and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.orbit.ant.generator;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class AntGenerator {

	public static void main(String[] args) throws Exception {
		var arguments = new ArrayList<>(Arrays.asList(args));

		var version = getArgument(arguments, "-version");

		var contentHandler = new ContentHandler(getArgument(arguments, "-cache"));

		var target = Path.of(getArgument(arguments, "-target")).toRealPath();
		var binary = contentHandler
				.getCachedContent("https://dlcdn.apache.org/ant/binaries/apache-ant-" + version + "-bin.zip");
		var jaiClasses = new LinkedHashSet<String>();
		var jars = new ArrayList<Path>(createBinaryArtifact(binary, target.resolve("artifact-bin.jar"), jaiClasses));
		var source = contentHandler
				.getCachedContent("https://dlcdn.apache.org/ant/source/apache-ant-" + version + "-src.zip")
				.toRealPath();
		createSourceArtifact(source, target.resolve("artifact-src.jar"), jaiClasses);

		var bndBundleClasspathInstruction = target.resolve("Bundle-ClassPath.properties");
		var bndBundleClasspathInstructions = new ArrayList<String>();
		bndBundleClasspathInstructions
				.add("# These contents are generated and if they change should be copied to MavenBDN.target");
		bndBundleClasspathInstructions.add("#");
		for (int i = 0, last = jars.size() - 1; i <= last; ++i) {
			bndBundleClasspathInstructions.add((i == 0 ? "Bundle-ClassPath:       " : "                        ")
					+ jars.get(i) + (i == last ? "" : ",\\"));
		}

		Files.write(bndBundleClasspathInstruction, bndBundleClasspathInstructions);

		System.out.println(String.join("\n", bndBundleClasspathInstructions));

		// Generate Import-Package instructions
		var importPackageFile = target.resolve("Import-Package.properties");
		var packageImports = generateImportPackageInstructions(version, jars, contentHandler);
		
		var importPackageInstructions = new ArrayList<String>();
		importPackageInstructions.add("# These contents are generated and if they change should be copied to MavenBDN.target");
		importPackageInstructions.add("#");
		importPackageInstructions.addAll(packageImports);
		
		Files.write(importPackageFile, importPackageInstructions);
		
		System.out.println("\n" + String.join("\n", importPackageInstructions));
	}

	private static Set<Path> createBinaryArtifact(Path source, Path target, Set<String> jaiClasses) throws IOException {
		var jars = new TreeSet<Path>();
		var ignoreResourcesPattern = Pattern.compile("/[^/]+/manual(/.*)?|.*jai\\.(pom|jar)");
		var libraryPattern = Pattern.compile("lib/.*\\.jar");
		Files.deleteIfExists(target);
		System.out.println("> " + source + " -> " + target);
		try (var sourceFileSystem = FileSystems.newFileSystem(source);
				var targetFileSystem = FileSystems.newFileSystem(target, Map.of("create", "true"));) {
			Files.walkFileTree(sourceFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					if (ignoreResourcesPattern.matcher(dir.toString()).matches()) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (ignoreResourcesPattern.matcher(file.toString()).matches()) {
						if ("ant-jai.jar".equals(file.getFileName().toString())) {
							collectJaiClasses(file, jaiClasses);
						}
						return FileVisitResult.SKIP_SUBTREE;
					}

					var relativePath = file.subpath(1, file.getNameCount());
					if (libraryPattern.matcher(relativePath.toString()).matches()) {
						jars.add(relativePath);
					}

					var targetFile = targetFileSystem.getPath("/").resolve(relativePath);
					Files.createDirectories(targetFile.getParent());
					System.out.println(" > " + file + " -> " + targetFile);

					Files.copy(file, targetFile);
					return FileVisitResult.CONTINUE;
				}
			});
		}

		return jars;
	}

	private static void collectJaiClasses(Path jai, Set<String> jaiClasses) throws IOException {
		try (var sourceFileSystem = FileSystems.newFileSystem(jai)) {
			Files.walkFileTree(sourceFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					var path = file.toString();
					if (path.endsWith(".class")) {
						// Keep the . for prefix matching later.
						jaiClasses.add(path.substring(0, path.length() - "class".length()));
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private static void createSourceArtifact(Path source, Path target, Set<String> jaiClasses) throws IOException {
		var includedResources = Pattern.compile("/[^/]+/src/main(/.*)?");
		Files.deleteIfExists(target);
		System.out.println("> " + source + " -> " + target);
		try (var sourceFileSystem = FileSystems.newFileSystem(source);
				var targetFileSystem = FileSystems.newFileSystem(target, Map.of("create", "true"));) {
			Files.walkFileTree(sourceFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					if (dir.getNameCount() > 3 && !includedResources.matcher(dir.toString()).matches()) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (!includedResources.matcher(file.toString()).matches()) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					var targetFile = targetFileSystem.getPath("/").resolve(file.subpath(3, file.getNameCount()));
					var targetFileString = targetFile.toString();
					for (var jaiClass : jaiClasses) {
						if (targetFileString.startsWith(jaiClass)) {
							System.out.println(" x " + file + " -> " + targetFile);
							return FileVisitResult.CONTINUE;
						}
					}

					Files.createDirectories(targetFile.getParent());
					System.out.println(" > " + file + " -> " + targetFile);

					Files.copy(file, targetFile);
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private static List<String> generateImportPackageInstructions(String version, List<Path> jars, ContentHandler contentHandler) throws Exception {
		System.out.println("\n=== Analyzing dependencies for Import-Package instructions ===");
		
		// Map to store package -> version information
		Map<String, String> packageVersions = new TreeMap<>();
		
		// Analyze each jar to find dependencies
		for (Path jar : jars) {
			String jarName = jar.getFileName().toString();
			if (jarName.startsWith("ant-") && jarName.endsWith(".jar")) {
				String artifactId = jarName.substring(0, jarName.length() - 4); // Remove .jar
				System.out.println("\nAnalyzing: " + artifactId);
				
				// Get dependencies from Maven POM
				List<MavenDependency> dependencies = fetchMavenDependencies("org.apache.ant", artifactId, version, contentHandler);
				
				// For each compile-scoped dependency, analyze packages
				for (MavenDependency dep : dependencies) {
					if ("compile".equals(dep.scope) && !dep.groupId.equals("org.apache.ant")) {
						System.out.println("  Dependency: " + dep.groupId + ":" + dep.artifactId + ":" + dep.version);
						analyzePackages(dep, packageVersions, contentHandler);
					}
				}
			}
		}
		
		// Generate Import-Package instruction
		List<String> instructions = new ArrayList<>();
		if (packageVersions.isEmpty()) {
			instructions.add("Import-Package:         *");
		} else {
			boolean first = true;
			for (Map.Entry<String, String> entry : packageVersions.entrySet()) {
				String packageName = entry.getKey();
				String versionRange = entry.getValue();
				
				String line;
				if (first) {
					line = "Import-Package:         " + packageName + versionRange + ",\\";
					first = false;
				} else {
					line = "                        " + packageName + versionRange + ",\\";
				}
				instructions.add(line);
			}
			// Add wildcard at the end for any other packages
			instructions.add("                        *;resolution:=optional");
		}
		
		return instructions;
	}

	private static List<MavenDependency> fetchMavenDependencies(String groupId, String artifactId, String version, ContentHandler contentHandler) throws Exception {
		List<MavenDependency> dependencies = new ArrayList<>();
		
		try {
			// Construct Maven Central POM URL
			String pomUrl = "https://repo1.maven.org/maven2/" + 
				groupId.replace('.', '/') + "/" + 
				artifactId + "/" + 
				version + "/" + 
				artifactId + "-" + version + ".pom";
			
			// Fetch and parse POM
			Path pomFile = contentHandler.getCachedContent(pomUrl);
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			Document doc = factory.newDocumentBuilder().parse(pomFile.toFile());
			
			NodeList depNodes = doc.getElementsByTagName("dependency");
			for (int i = 0; i < depNodes.getLength(); i++) {
				Element depElement = (Element) depNodes.item(i);
				
				String depGroupId = getElementText(depElement, "groupId");
				String depArtifactId = getElementText(depElement, "artifactId");
				String depVersion = getElementText(depElement, "version");
				String scope = getElementText(depElement, "scope");
				
				if (scope == null || scope.isEmpty()) {
					scope = "compile"; // Default scope
				}
				
				if (depGroupId != null && depArtifactId != null && depVersion != null) {
					dependencies.add(new MavenDependency(depGroupId, depArtifactId, depVersion, scope));
				}
			}
		} catch (Exception e) {
			System.err.println("  Warning: Could not fetch POM for " + groupId + ":" + artifactId + ":" + version);
			System.err.println("  " + e.getMessage());
		}
		
		return dependencies;
	}

	private static String getElementText(Element parent, String tagName) {
		NodeList nodes = parent.getElementsByTagName(tagName);
		if (nodes.getLength() > 0) {
			return nodes.item(0).getTextContent().trim();
		}
		return null;
	}

	private static void analyzePackages(MavenDependency dep, Map<String, String> packageVersions, ContentHandler contentHandler) {
		try {
			// Download dependency JAR
			String jarUrl = "https://repo1.maven.org/maven2/" + 
				dep.groupId.replace('.', '/') + "/" + 
				dep.artifactId + "/" + 
				dep.version + "/" + 
				dep.artifactId + "-" + dep.version + ".jar";
			
			Path jarFile = contentHandler.getCachedContent(jarUrl);
			
			// Analyze JAR for exported packages
			try (JarFile jar = new JarFile(jarFile.toFile())) {
				Manifest manifest = jar.getManifest();
				if (manifest != null) {
					Attributes mainAttributes = manifest.getMainAttributes();
					String exportPackage = mainAttributes.getValue("Export-Package");
					
					if (exportPackage != null && !exportPackage.isEmpty()) {
						// Parse Export-Package header
						parseExportPackageHeader(exportPackage, dep.version, packageVersions);
					} else {
						// If no Export-Package header, scan classes
						analyzeClassFiles(jar, dep.version, packageVersions);
					}
				} else {
					// No manifest, scan classes
					analyzeClassFiles(jar, dep.version, packageVersions);
				}
			}
			
			System.out.println("    Analyzed packages from " + dep.artifactId);
		} catch (Exception e) {
			System.err.println("    Warning: Could not analyze JAR for " + dep.groupId + ":" + dep.artifactId);
			System.err.println("    " + e.getMessage());
		}
	}

	private static void parseExportPackageHeader(String exportPackage, String version, Map<String, String> packageVersions) {
		// Parse OSGi Export-Package header
		// Format: package;version="1.0.0",package2;version="2.0.0"
		String[] packages = exportPackage.split(",");
		for (String pkg : packages) {
			pkg = pkg.trim();
			if (pkg.isEmpty()) continue;
			
			// Extract package name and version
			String[] parts = pkg.split(";");
			String packageName = parts[0].trim();
			
			// Extract version from attributes
			String pkgVersion = null;
			for (int i = 1; i < parts.length; i++) {
				String attr = parts[i].trim();
				if (attr.startsWith("version=")) {
					pkgVersion = attr.substring(8).replaceAll("\"", "").trim();
					break;
				}
			}
			
			if (pkgVersion == null) {
				pkgVersion = version;
			}
			
			// Generate version range [X.Y.Z,X+1)
			String versionRange = generateVersionRange(pkgVersion);
			packageVersions.put(packageName, versionRange);
		}
	}

	private static void analyzeClassFiles(JarFile jar, String version, Map<String, String> packageVersions) {
		// Scan JAR entries for class files to determine packages
		Set<String> packages = new TreeSet<>();
		jar.stream()
			.filter(entry -> entry.getName().endsWith(".class"))
			.filter(entry -> !entry.getName().startsWith("META-INF/"))
			.forEach(entry -> {
				String className = entry.getName();
				int lastSlash = className.lastIndexOf('/');
				if (lastSlash > 0) {
					String packageName = className.substring(0, lastSlash).replace('/', '.');
					packages.add(packageName);
				}
			});
		
		// Add packages with version range
		String versionRange = generateVersionRange(version);
		for (String pkg : packages) {
			packageVersions.put(pkg, versionRange);
		}
	}

	private static String generateVersionRange(String version) {
		// Parse version and generate range [X.Y.Z, X+1)
		try {
			String[] parts = version.split("\\.");
			if (parts.length >= 1) {
				int major = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
				// Format: ;version="[0.1.55,1)"
				return ";version=\"[" + version + "," + (major + 1) + ")\"";
			}
		} catch (Exception e) {
			// If version parsing fails, use simple range
		}
		return ";version=\"" + version + "\"";
	}

	private static class MavenDependency {
		String groupId;
		String artifactId;
		String version;
		String scope;
		
		MavenDependency(String groupId, String artifactId, String version, String scope) {
			this.groupId = groupId;
			this.artifactId = artifactId;
			this.version = version;
			this.scope = scope;
		}
	}

	private static String getArgument(List<String> arguments, String name) {
		var index = arguments.indexOf(name);
		if (index >= 0) {
			arguments.remove(index);
			if (index < arguments.size()) {
				return arguments.remove(index);
			}
		}

		return null;
	}

	private static class ContentHandler {
		private final Path cache;

		private final HttpClient httpClient;

		public ContentHandler(String cache) {
			httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
					.cookieHandler(new CookieManager()).build();
			try {
				if (cache != null) {
					this.cache = Path.of(cache);
				} else {
					this.cache = Files.createTempDirectory("org.eclipse.orbit.ant-generator-cache");
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		protected <T> T basicGetContent(URI uri, BodyHandler<T> bodyHandler) throws IOException, InterruptedException {
			var requestBuilder = HttpRequest.newBuilder(uri).GET();
			var request = requestBuilder.build();
			var response = httpClient.send(request, bodyHandler);
			var statusCode = response.statusCode();
			if (statusCode != 200) {
				throw new IOException("status code " + statusCode + " -> " + uri);
			}

			return response.body();
		}

		protected Path getCachePath(URI uri) {
			var decodedURI = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
			var isFolder = decodedURI.endsWith("/");
			var uriSegments = decodedURI.split("[:/?#&;]+");
			var relativePath = String.join("/", uriSegments).replace('=', '-');
			if (isFolder) {
				relativePath += "_._";
			}

			var result = cache.resolve(relativePath);
			return result;
		}

		public Path getCachedContent(String uri) throws IOException {
			return getCachedContent(URI.create(uri));
		}

		public Path getCachedContent(URI uri) throws IOException {
			var path = getCachePath(uri);
			if (!isCacheFresh(path)) {
				try {
					var content = basicGetContent(uri, BodyHandlers.ofInputStream());
					writeContent(path, content);
				} catch (InterruptedException e) {
					throw new IOException(e);
				}
			}

			return path;
		}

		private boolean isCacheFresh(Path path) throws IOException {
			if (Files.isRegularFile(path)) {
				var lastModifiedTime = Files.getLastModifiedTime(path);
				var now = System.currentTimeMillis();
				var age = now - lastModifiedTime.toMillis();
				var ageInHours = age / 1000 / 60 / 60;
				if (ageInHours < 8 * 3 * 7) {
					return true;
				}
			}

			return false;
		}

		private void writeContent(Path path, InputStream content) throws IOException {
			createDirectories(path);
			Files.copy(content, path);
		}

		private synchronized void createDirectories(Path path) throws IOException {
			Files.createDirectories(path.getParent());
		}
	}

}
