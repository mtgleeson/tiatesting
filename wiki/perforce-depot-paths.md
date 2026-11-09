# Perforce depot paths

Tia's Perforce reader compares depot paths from two kinds of command. `p4 files` and `p4 fstat`
report a file's depot path in the case it was stored in. `p4 where`, which Tia uses to resolve the
configured source and test directories and each changed file's local path, reports the depot path in
the **client view's** case for the part the view maps.

On a case-sensitive server the two always agree. On a case-insensitive server (a Windows- or
macOS-hosted server reports `Case Handling: insensitive` in `p4 info`) they differ whenever the client
view spells the depot path in another case than the stored files - typically a hand-written classic
client view such as `//Depot/Apps/... //ws/...` over files stored under `//depot/apps/...`. Stream
clients generate their view from the stream name and normally match.

Tia follows the server's case rule in every comparison between the two (`P4DiffAnalyzer.indexOfDepotPath`,
`depotPathKey`). p4java learns the rule when it connects, so this costs no extra command.

If `p4 where` doesn't map a changed source or test file at all, the file is skipped and Tia logs one
WARN naming every such file: the file can't be located in the workspace, so its changes can't be
matched to tracked methods and tests for them aren't selected.
