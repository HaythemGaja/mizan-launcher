#!/usr/bin/env python3
"""Import-graph + layer checker for the Mizan rebuild tree.

Fails (non-zero exit) when:
  1. any import cycle exists across OR within layers (file-level graph,
     Tarjan SCC: an SCC of >1 file, or a self-import, is a cycle), or
  2. a layer imports a layer above it:
       core   may import: core only   (+ java/kotlin stdlib, nothing else
                                        from com.mizan.launcher)
       domain may import: core, domain (and NO android.* at all)
       data   may import: core, domain, data
       ui     may import: core, domain, data, ui
       app    may import: core, domain, data, ui, app
     i.e. data must never import ui/app, domain must never import
     data/ui/app, core must never import domain/data/ui/app, ui must
     never import app.
  3. a fully-qualified reference (com.mizan.launcher.<layer>.* in a
     non-import line) violates the same direction — this catches the
     old tree's TANGLE-3 pattern (no import statement, same violation).

Usage: python3 tools/check-cycles.py [tree-root]
  Default tree root: app/src/main/java relative to the repo root
  (the directory containing tools/). Exit 0 = clean, 1 = violations.
"""
import os
import re
import sys
from collections import defaultdict

LAYERS = ["core", "domain", "data", "ui", "app"]
LAYER_OF_PACKAGE = {
    "com.mizan.launcher.core": "core",
    "com.mizan.launcher.domain": "domain",
    "com.mizan.launcher.data": "data",
    "com.mizan.launcher.ui": "ui",
    "com.mizan.launcher.app": "app",
    # Bare root package is the legacy root layer = app layer.
    "com.mizan.launcher": "app",
}

IMPORT_RE = re.compile(r"^\s*import\s+([A-Za-z0-9_.]+)(\.\*)?\s*$")
PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*$")
DECL_RE = re.compile(
    r"^\s*(?:public\s+|internal\s+|private\s+|open\s+|abstract\s+|sealed\s+|data\s+)*"
    r"(?:class|object|interface|enum class)\s+([A-Za-z0-9_]+)"
)
FQ_RE = re.compile(r"com\.mizan\.launcher\.(core|domain|data|ui|app)\b")
ANDROID_IMPORT_RE = re.compile(r"^\s*import\s+android\.")


def layer_allowed(src, dst):
    """True if layer src may depend on layer dst (downward or same)."""
    return LAYERS.index(src) >= LAYERS.index(dst)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else None
    if root is None:
        here = os.path.dirname(os.path.abspath(__file__))
        root = os.path.join(os.path.dirname(here), "app", "src", "main", "java")
    files = []
    for dirpath, _dirs, names in os.walk(root):
        for name in sorted(names):
            if name.endswith(".kt"):
                files.append(os.path.join(dirpath, name))
    if not files:
        print("check-cycles: no Kotlin files found under %s" % root)
        return 1

    # Map (package, declared top-level type name) -> file, plus a
    # bare type-name fallback, and file -> layer.
    type_to_file = {}
    file_layer = {}
    file_pkg = {}
    for path in files:
        pkg = None
        with open(path, encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
        for line in lines:
            m = PACKAGE_RE.match(line)
            if m:
                pkg = m.group(1)
                break
        if pkg is None:
            print("VIOLATION: %s has no package declaration" % path)
            return 1
        layer = LAYER_OF_PACKAGE.get(pkg)
        if layer is None:
            print("VIOLATION: %s uses unknown package %s" % (path, pkg))
            return 1
        file_pkg[path] = pkg
        file_layer[path] = layer
        for line in lines:
            m = DECL_RE.match(line)
            if m:
                type_to_file.setdefault((pkg, m.group(1)), path)
                type_to_file.setdefault(m.group(1), path)

    edges = defaultdict(set)  # src file -> set of dst files
    violations = []

    def add_edge(src, dst, why):
        if dst == src:
            return
        if dst not in file_layer:
            return
        edges[src].add(dst)
        sl, dl = file_layer[src], file_layer[dst]
        if not layer_allowed(sl, dl):
            violations.append(
                "%s (%s) -> %s (%s): %s" % (src, sl, dst, dl, why)
            )

    for path in files:
        pkg = file_pkg[path]
        with open(path, encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
        for line in lines:
            m = IMPORT_RE.match(line)
            if m:
                imp = m.group(1)
                if imp.startswith("com.mizan.launcher."):
                    # com.mizan.launcher.<layer>.<Type> (or the layer pkg itself)
                    parts = imp.split(".")
                    if len(parts) >= 4:
                        layer = parts[3]
                        if layer in LAYERS:
                            dst_pkg = "com.mizan.launcher." + layer
                            # Resolve by (package, type name) first, so
                            # same-named types in different layers (e.g.
                            # core LiveTv vs data LiveTv) don't confuse it.
                            typename = parts[-1] if len(parts) > 4 else None
                            imp_pkg = ".".join(parts[:-1]) if typename else dst_pkg
                            dst = type_to_file.get((imp_pkg, typename)) if typename else None
                            if dst is None:
                                dst = type_to_file.get(typename) if typename else None
                            if dst is None:
                                # Fall back: any file in that layer package.
                                dst = next(
                                    (f for f in files if file_pkg[f] == dst_pkg),
                                    None,
                                )
                            if dst is not None:
                                add_edge(path, dst, "import %s" % imp)
                            else:
                                # Import of a not-yet-written layer file:
                                # still enforce direction by layer name.
                                sl, dl = file_layer[path], layer
                                if not layer_allowed(sl, dl):
                                    violations.append(
                                        "%s (%s) -> <missing %s file> (%s): import %s"
                                        % (path, sl, dl, dl, imp)
                                    )
                elif imp == "com.mizan.launcher":
                    pass
            # android.* rules: domain must import no android.* at all;
            # core must import no android.* at all (pure logic).
            if ANDROID_IMPORT_RE.match(line):
                if file_layer[path] in ("core", "domain"):
                    violations.append(
                        "%s (%s) imports Android framework type: %s"
                        % (path, file_layer[path], line.strip())
                    )
        # Fully-qualified references outside import lines.
        for line in lines:
            if line.lstrip().startswith("import "):
                continue
            for m in FQ_RE.finditer(line):
                layer = m.group(1)
                # Find a file in that layer (direction check by layer).
                sl, dl = file_layer[path], layer
                if not layer_allowed(sl, dl):
                    violations.append(
                        "%s (%s) -> %s layer: fully-qualified reference: %s"
                        % (path, sl, dl, line.strip()[:120])
                    )

    # Tarjan SCC for cycles over the file graph.
    index_counter = [0]
    stack, lowlink, index, on_stack = [], {}, {}, set()
    sccs = []

    sys.setrecursionlimit(10000)

    def strongconnect(v):
        index[v] = index_counter[0]
        lowlink[v] = index_counter[0]
        index_counter[0] += 1
        stack.append(v)
        on_stack.add(v)
        for w in sorted(edges.get(v, ())):
            if w not in index:
                strongconnect(w)
                lowlink[v] = min(lowlink[v], lowlink[w])
            elif w in on_stack:
                lowlink[v] = min(lowlink[v], index[w])
        if lowlink[v] == index[v]:
            scc = []
            while True:
                w = stack.pop()
                on_stack.remove(w)
                scc.append(w)
                if w == v:
                    break
            if len(scc) > 1:
                scc.sort()
                violations.append(
                    "IMPORT CYCLE (%d files): %s"
                    % (len(scc), " -> ".join(scc) + " -> " + scc[0])
                )

    for v in sorted(files):
        if v not in index:
            strongconnect(v)

    # Self-imports.
    for path in files:
        if path in edges.get(path, set()):
            violations.append("SELF-IMPORT: %s" % path)

    print("check-cycles: %d Kotlin files, %d import edges" % (len(files), sum(len(v) for v in edges.values())))
    for layer in LAYERS:
        n = sum(1 for f in files if file_layer[f] == layer)
        print("  layer %-6s: %d file(s)" % (layer, n))
    if violations:
        print("FAIL: %d violation(s):" % len(violations))
        for v in sorted(set(violations)):
            print("  - " + v)
        return 1
    print("PASS: no import cycles, no upward layer imports")
    return 0


if __name__ == "__main__":
    sys.exit(main())
