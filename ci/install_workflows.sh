#!/usr/bin/env bash
#
# Move the staged workflow files in ci/workflows/ into .github/workflows/.
#
# Why this script exists
# ----------------------
# The pull request that introduced the unmerged test build feature could not
# place these files in .github/workflows/ directly: the token used to push it
# is not allowed to create or update workflow files. The files are therefore
# parked in ci/workflows/ where they are inert, and a repository owner runs
# this script once to put them in the place GitHub Actions reads.
#
# Usage
# -----
#   bash ci/install_workflows.sh            # show what would move
#   bash ci/install_workflows.sh --apply    # move the files and stage the change
#
# After --apply, review "git status", commit, and push with an account that may
# update workflows (a normal personal push is enough).

set -euo pipefail

SOURCE_DIR="ci/workflows"
TARGET_DIR=".github/workflows"
APPLY=0

for argument in "$@"; do
    case "$argument" in
        --apply)
            APPLY=1
            ;;
        -h | --help)
            sed -n '2,/^$/p' "$0" | sed 's/^#\{1,\} \{0,1\}//;s/^#$//'
            exit 0
            ;;
        *)
            echo "unknown option: $argument" >&2
            echo "run with --help for usage" >&2
            exit 2
            ;;
    esac
done

repository_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$repository_root" ]; then
    echo "run this from inside the repository" >&2
    exit 1
fi
cd "$repository_root"

if [ ! -d "$SOURCE_DIR" ]; then
    echo "nothing to install: $SOURCE_DIR does not exist"
    echo "the workflows are most likely already in $TARGET_DIR"
    exit 0
fi

shopt -s nullglob
staged_files=("$SOURCE_DIR"/*.yml)
shopt -u nullglob

if [ "${#staged_files[@]}" -eq 0 ]; then
    echo "nothing to install: no .yml files in $SOURCE_DIR"
    exit 0
fi

if [ "$APPLY" -eq 0 ]; then
    echo "Would install ${#staged_files[@]} workflow file(s) into $TARGET_DIR:"
    for file in "${staged_files[@]}"; do
        name="$(basename "$file")"
        if [ -e "$TARGET_DIR/$name" ]; then
            echo "  $name  (replaces the existing file)"
        else
            echo "  $name  (new file)"
        fi
    done
    echo
    echo "Re-run with --apply to perform the move."
    exit 0
fi

mkdir -p "$TARGET_DIR"

for file in "${staged_files[@]}"; do
    name="$(basename "$file")"
    if [ -e "$TARGET_DIR/$name" ]; then
        git rm --quiet --force "$TARGET_DIR/$name" >/dev/null 2>&1 || rm -f "$TARGET_DIR/$name"
    fi
    # "git rm" deletes the directory too once it holds no tracked files.
    mkdir -p "$TARGET_DIR"
    if git ls-files --error-unmatch "$file" >/dev/null 2>&1; then
        git mv "$file" "$TARGET_DIR/$name"
    else
        mv "$file" "$TARGET_DIR/$name"
        git add "$TARGET_DIR/$name"
    fi
    echo "installed $TARGET_DIR/$name"
done

rmdir "$SOURCE_DIR" 2>/dev/null || true

cat <<'DONE'

Done. Next steps:
  1. git status                 review the move
  2. git commit -m "ci(actions): install pull request test build workflows"
  3. git push
  4. Open the Actions tab and confirm "Unmerged pull request test build" and
     "Unmerged pull request test build cleanup" are listed.
DONE
