# City label redactor

This standalone tool detects cyan player or alliance text printed inside a
horizontal grey nameplate. It covers only the detected print area with an
opaque cyan block, preserving the nameplate border and the fixed right-side
button column. Green labels such as `My City` are intentionally left unchanged.

The empirical coordinate text and chat regions from the standard `720x1280`
viewport are also covered with opaque grey-blue blocks. Those regions scale
proportionally for other image sizes. The tool uses only the Java standard
library and does not build or start Frostguard.

Run it from the repository root with Java 21 or newer:

```sh
java tools/privacy-redactor/CityLabelRedactor.java input.png output.png
```

The same command works from PowerShell or Command Prompt when run at the
repository root.

The output is always a new PNG; the input file is never modified. Review the
result before sharing or committing it. The tool does not currently redact
profile names, account identifiers, or private text outside the detected city
labels, coordinate bar, and chat region.

Run its standalone regression tests from the repository root:

```sh
./mvnw -f tools/privacy-redactor/pom.xml test
```

The tests compare synthetic input snapshots containing fake names with their
reviewed expected PNG outputs. They also reprocess the reviewed real-frame
fixtures to prove that redaction is idempotent. Raw identifying screenshots are
never test resources.
