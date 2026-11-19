import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves and downloads pure-Java / classes.jar artifacts for Media3 ExoPlayer + HLS
 * without Gradle or Python, stripping any .so native libraries from .aar archives.
 */
public class FetchMedia3 {
    private static final String[] REPOS = {
            "https://dl.google.com/dl/android/maven2",
            "https://repo.maven.apache.org/maven2"
    };

    private static final Set<String> EXCLUDED_GROUP_ARTIFACTS = new HashSet<>(Arrays.asList(
            "com.google.code.findbugs:jsr305",
            "org.checkerframework:checker-qual",
            "com.google.errorprone:error_prone_annotations",
            "com.google.j2objc:j2objc-annotations",
            "org.codehaus.mojo:animal-sniffer-annotations"
    ));

    private static final class Dep {
        String group;
        String artifact;
        String version;
        String scope;
        boolean optional;
        String type;
        Set<String> exclusions = new HashSet<>();

        String ga() { return group + ":" + artifact; }
    }

    private static final class Pom {
        String group = "";
        String artifact = "";
        String version = "";
        String packaging = "jar";
        String parentGroup = "";
        String parentArtifact = "";
        String parentVersion = "";
        Map<String, String> props = new LinkedHashMap<>();
        Map<String, String> managedVersions = new LinkedHashMap<>();
        List<Dep> deps = new ArrayList<>();
    }

    private static final Map<String, Pom> POM_CACHE = new HashMap<>();

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "libs/media3/jars");
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Cannot create dir: " + outDir);
        }

        List<Dep> queue = new ArrayList<>();
        queue.add(rootDep("androidx.media3", "media3-exoplayer", "1.5.1"));
        queue.add(rootDep("androidx.media3", "media3-exoplayer-hls", "1.5.1"));

        Map<String, Dep> selected = new LinkedHashMap<>();
        ArrayDeque<Dep> bfs = new ArrayDeque<>(queue);
        Set<String> processedGav = new HashSet<>();

        while (!bfs.isEmpty()) {
            Dep d = bfs.poll();
            if (d.group.isEmpty() || d.artifact.isEmpty() || d.version.isEmpty()) continue;
            String ga = d.ga();
            if (EXCLUDED_GROUP_ARTIFACTS.contains(ga)) continue;
            // Skip test/annotation/tooling artifacts that have no runtime classes
            if (d.artifact.endsWith("-empty-to-avoid-conflict-with-guava")) continue;

            Dep prev = selected.get(ga);
            if (prev != null) {
                if (compareVersion(d.version, prev.version) <= 0) {
                    continue;
                }
            }
            selected.put(ga, d);

            String gav = ga + ":" + d.version;
            if (!processedGav.add(gav)) continue;

            Pom pom = loadResolvedPom(d.group, d.artifact, d.version);
            if (pom == null) {
                System.err.println("WARN: POM not found for " + gav);
                continue;
            }
            for (Dep child : pom.deps) {
                if (child.optional) continue;
                String sc = child.scope == null ? "" : child.scope;
                if (!sc.isEmpty() && !"compile".equals(sc) && !"runtime".equals(sc)) continue;
                if (d.exclusions.contains(child.ga()) || d.exclusions.contains(child.group + ":*")
                        || d.exclusions.contains("*:*")) {
                    continue;
                }
                Dep next = new Dep();
                next.group = child.group;
                next.artifact = child.artifact;
                next.version = resolveVersion(child.version);
                next.scope = sc;
                next.type = child.type;
                next.exclusions.addAll(d.exclusions);
                next.exclusions.addAll(child.exclusions);
                if (!next.version.isEmpty()) {
                    bfs.add(next);
                }
            }
        }

        int jarCount = 0;
        for (Dep d : selected.values()) {
            if ("com.google.guava".equals(d.group) && "listenablefuture".equals(d.artifact)
                    && d.version.contains("9999.0")) {
                continue;
            }
            Pom pom = loadResolvedPom(d.group, d.artifact, d.version);
            String pkg = pom != null ? pom.packaging : (d.type != null && !d.type.isEmpty() ? d.type : "jar");
            if ("pom".equals(pkg)) continue;

            String baseName = d.group + "__" + d.artifact + "-" + d.version;
            if ("aar".equals(pkg) || "bundle".equals(pkg)) {
                byte[] bytes = downloadArtifact(d.group, d.artifact, d.version, "aar");
                if (bytes == null && "bundle".equals(pkg)) {
                    bytes = downloadArtifact(d.group, d.artifact, d.version, "jar");
                    if (bytes != null) {
                        writeBytes(new File(outDir, baseName + ".jar"), bytes);
                        jarCount++;
                        System.out.println("JAR: " + baseName);
                        continue;
                    }
                }
                if (bytes == null) {
                    throw new IOException("Failed to download AAR: " + d.ga() + ":" + d.version);
                }
                int extracted = extractAarJars(bytes, outDir, baseName);
                jarCount += extracted;
                System.out.println("AAR (" + extracted + " jar): " + baseName);
            } else {
                byte[] bytes = downloadArtifact(d.group, d.artifact, d.version, "jar");
                if (bytes == null) {
                    bytes = downloadArtifact(d.group, d.artifact, d.version, "aar");
                    if (bytes != null) {
                        int extracted = extractAarJars(bytes, outDir, baseName);
                        jarCount += extracted;
                        System.out.println("AAR (" + extracted + " jar): " + baseName);
                        continue;
                    }
                    throw new IOException("Failed to download JAR: " + d.ga() + ":" + d.version);
                }
                writeBytes(new File(outDir, baseName + ".jar"), bytes);
                jarCount++;
                System.out.println("JAR: " + baseName);
            }
        }
        System.out.println("DONE: total " + jarCount + " jar files in " + outDir.getAbsolutePath());
    }

    private static Dep rootDep(String g, String a, String v) {
        Dep d = new Dep();
        d.group = g;
        d.artifact = a;
        d.version = v;
        d.scope = "compile";
        return d;
    }

    private static String resolveVersion(String v) {
        if (v == null) return "";
        v = v.trim();
        // Handle Maven version ranges like [1.8.0] or [1.0,2.0)
        if ((v.startsWith("[") || v.startsWith("(")) && (v.endsWith("]") || v.endsWith(")"))) {
            String inner = v.substring(1, v.length() - 1).trim();
            String[] parts = inner.split(",");
            if (parts.length == 1) return parts[0].trim();
            if (!parts[parts.length - 1].trim().isEmpty()) return parts[parts.length - 1].trim();
            return parts[0].trim();
        }
        return v;
    }

    private static Pom loadResolvedPom(String g, String a, String v) throws Exception {
        String key = g + ":" + a + ":" + v;
        if (POM_CACHE.containsKey(key)) return POM_CACHE.get(key);

        byte[] data = downloadArtifact(g, a, v, "pom");
        if (data == null) {
            POM_CACHE.put(key, null);
            return null;
        }
        Pom raw = parsePomXml(data);
        if (raw.group.isEmpty()) raw.group = g;
        if (raw.artifact.isEmpty()) raw.artifact = a;
        if (raw.version.isEmpty()) raw.version = v;

        Map<String, String> mergedProps = new LinkedHashMap<>();
        Map<String, String> mergedManaged = new LinkedHashMap<>();
        if (!raw.parentGroup.isEmpty() && !raw.parentArtifact.isEmpty() && !raw.parentVersion.isEmpty()) {
            Pom parent = loadResolvedPom(raw.parentGroup, raw.parentArtifact, raw.parentVersion);
            if (parent != null) {
                mergedProps.putAll(parent.props);
                mergedManaged.putAll(parent.managedVersions);
            }
        }
        mergedProps.put("project.groupId", raw.group);
        mergedProps.put("project.artifactId", raw.artifact);
        mergedProps.put("project.version", raw.version);
        mergedProps.put("pom.groupId", raw.group);
        mergedProps.put("pom.artifactId", raw.artifact);
        mergedProps.put("pom.version", raw.version);
        if (!raw.parentVersion.isEmpty()) mergedProps.put("parent.version", raw.parentVersion);
        if (!raw.parentGroup.isEmpty()) mergedProps.put("parent.groupId", raw.parentGroup);
        for (Map.Entry<String, String> e : raw.props.entrySet()) {
            mergedProps.put(e.getKey(), substitute(e.getValue(), mergedProps));
        }

        // Import BOMs declared in managedVersions? Handle raw deps and managedVersions
        for (Map.Entry<String, String> e : raw.managedVersions.entrySet()) {
            String mk = substitute(e.getKey(), mergedProps);
            String mv = substitute(e.getValue(), mergedProps);
            mergedManaged.put(mk, mv);
        }
        // Check BOM imports stored under special prefix "BOM:"
        List<String> bomKeys = new ArrayList<>();
        for (String k : mergedManaged.keySet()) {
            if (k.startsWith("BOM:")) bomKeys.add(k);
        }
        for (String bk : bomKeys) {
            String ver = mergedManaged.remove(bk);
            String[] parts = bk.substring(4).split(":");
            if (parts.length == 2) {
                Pom bom = loadResolvedPom(parts[0], parts[1], ver);
                if (bom != null) {
                    for (Map.Entry<String, String> be : bom.managedVersions.entrySet()) {
                        mergedManaged.putIfAbsent(be.getKey(), be.getValue());
                    }
                }
            }
        }

        raw.props = mergedProps;
        raw.managedVersions = mergedManaged;
        for (Dep d : raw.deps) {
            d.group = substitute(d.group, mergedProps);
            d.artifact = substitute(d.artifact, mergedProps);
            d.version = substitute(d.version, mergedProps);
            if (d.version.isEmpty()) {
                String mv = mergedManaged.get(d.ga());
                if (mv != null) d.version = substitute(mv, mergedProps);
            }
            d.scope = substitute(d.scope, mergedProps);
        }

        POM_CACHE.put(key, raw);
        return raw;
    }

    private static String substitute(String val, Map<String, String> props) {
        if (val == null) return "";
        String cur = val.trim();
        for (int pass = 0; pass < 5 && cur.contains("${"); pass++) {
            int s = cur.indexOf("${");
            int e = cur.indexOf('}', s + 2);
            if (e < 0) break;
            String name = cur.substring(s + 2, e);
            String rep = props.get(name);
            if (rep == null) break;
            cur = cur.substring(0, s) + rep + cur.substring(e + 1);
        }
        return cur;
    }

    private static Pom parsePomXml(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        Element root = doc.getDocumentElement();
        Pom pom = new Pom();

        pom.group = childText(root, "groupId");
        pom.artifact = childText(root, "artifactId");
        pom.version = childText(root, "version");
        String pkg = childText(root, "packaging");
        if (!pkg.isEmpty()) pom.packaging = pkg;

        Element parent = firstChild(root, "parent");
        if (parent != null) {
            pom.parentGroup = childText(parent, "groupId");
            pom.parentArtifact = childText(parent, "artifactId");
            pom.parentVersion = childText(parent, "version");
            if (pom.group.isEmpty()) pom.group = pom.parentGroup;
            if (pom.version.isEmpty()) pom.version = pom.parentVersion;
        }

        Element propsEl = firstChild(root, "properties");
        if (propsEl != null) {
            NodeList nl = propsEl.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n instanceof Element) {
                    pom.props.put(n.getNodeName(), n.getTextContent().trim());
                }
            }
        }

        Element dm = firstChild(root, "dependencyManagement");
        if (dm != null) {
            Element deps = firstChild(dm, "dependencies");
            if (deps != null) {
                parseDepList(deps, pom.deps, pom.managedVersions, true);
            }
        }

        Element deps = firstChild(root, "dependencies");
        if (deps != null) {
            parseDepList(deps, pom.deps, pom.managedVersions, false);
        }
        return pom;
    }

    private static void parseDepList(Element depsEl, List<Dep> outDeps,
                                     Map<String, String> managed, boolean isManagement) {
        NodeList nl = depsEl.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element) || !"dependency".equals(n.getNodeName())) continue;
            Element el = (Element) n;
            String g = childText(el, "groupId");
            String a = childText(el, "artifactId");
            String v = childText(el, "version");
            String scope = childText(el, "scope");
            String type = childText(el, "type");
            boolean opt = "true".equalsIgnoreCase(childText(el, "optional"));
            if (isManagement) {
                if ("import".equals(scope) && "pom".equals(type) && !v.isEmpty()) {
                    managed.put("BOM:" + g + ":" + a, v);
                } else if (!g.isEmpty() && !a.isEmpty() && !v.isEmpty()) {
                    managed.put(g + ":" + a, v);
                }
            } else {
                Dep d = new Dep();
                d.group = g;
                d.artifact = a;
                d.version = v;
                d.scope = scope;
                d.type = type;
                d.optional = opt;
                Element exs = firstChild(el, "exclusions");
                if (exs != null) {
                    NodeList enl = exs.getChildNodes();
                    for (int j = 0; j < enl.getLength(); j++) {
                        Node en = enl.item(j);
                        if (en instanceof Element && "exclusion".equals(en.getNodeName())) {
                            String eg = childText((Element) en, "groupId");
                            String ea = childText((Element) en, "artifactId");
                            d.exclusions.add(eg + ":" + ea);
                        }
                    }
                }
                outDeps.add(d);
            }
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && tag.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        Element c = firstChild(parent, tag);
        return c == null ? "" : c.getTextContent().trim();
    }

    private static byte[] downloadArtifact(String g, String a, String v, String ext) throws IOException {
        String path = g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v + "." + ext;
        for (String repo : REPOS) {
            byte[] b = fetchUrl(repo + "/" + path);
            if (b != null) return b;
        }
        return null;
    }

    private static byte[] fetchUrl(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "DouyinTV-FetchMedia3/1.0");
        int code = conn.getResponseCode();
        if (code == 404 || code == 403 || code >= 400) {
            conn.disconnect();
            return null;
        }
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static int extractAarJars(byte[] aarBytes, File outDir, String baseName) throws IOException {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(aarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                // Strictly extract ONLY Java .jar files; ignore all .so native libraries!
                if ("classes.jar".equals(name)) {
                    writeBytes(new File(outDir, baseName + ".jar"), readAll(zis));
                    count++;
                } else if (name.startsWith("libs/") && name.endsWith(".jar")) {
                    String leaf = name.substring(name.lastIndexOf('/') + 1);
                    writeBytes(new File(outDir, baseName + "__" + leaf), readAll(zis));
                    count++;
                }
            }
        }
        return count;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void writeBytes(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    private static int compareVersion(String a, String b) {
        String[] pa = a.split("[.-]");
        String[] pb = b.split("[.-]");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            int ia = parseDigits(sa);
            int ib = parseDigits(sb);
            if (ia >= 0 && ib >= 0) {
                if (ia != ib) return Integer.compare(ia, ib);
            }
            int c = sa.compareTo(sb);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int parseDigits(String s) {
        if (s.isEmpty()) return -1;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return -1;
        }
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }
}
