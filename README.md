# bb-build — scaffold a hive project's build setup (lein-new style)

`bb-build` instantiates the release setup for a Clojure repo the way
`lein new` instantiates a project: pick a template **kind**, and it writes
`version.edn` + the release workflow and prints the `:build` alias to add.

It is the companion to **hive-build**, which is a *pure library*: a scaffolded
repo consumes `hive-build.api` through its `:build` alias — it never gets a
copied `build.clj`. `bb-build` generates the wiring; `hive-build.api` does the
building.

## Install

```bash
bbin install io.github.hive-agi/bb-build
# or, from a checkout:
cd bb-build && bb bin/bb-build   # runs the CLI directly
```

## Use

```bash
bb-build new <lib-short-name> <target-dir> [opts]

# public library published to Clojars (autodetected from a github origin):
bb-build new hive-help ../hive-help

# private library published to the Gitea Maven registry:
bb-build new hive-premium ../hive-premium --kind gitea
```

It writes:

- `version.edn` — `{:lib :minor :license :scm-url :src-dirs :publish}`, the
  single source of truth `hive-build.api` reads for the Maven coord and git tag.
- the release workflow — `.github/workflows/release.yml` (Clojars) or
  `.gitea/workflows/release.yml` (Gitea). Never both.

and prints the `:build` alias to paste under `:aliases` in the repo's
`deps.edn`:

```clojure
:build {:deps {io.github.hive-agi/hive-build {:mvn/version "0.1.0"}}
        :jvm-opts ["-Xmx1g"]
        :ns-default hive-build.api}
```

Then verify locally (writes only to `~/.m2`, no network):

```bash
(cd ../hive-help && clojure -T:build install)
```

## Kinds

| kind      | repo host    | publishes to        | license default | workflow                         |
|-----------|--------------|---------------------|-----------------|----------------------------------|
| `clojars` | public GitHub| Clojars             | MIT             | `.github/workflows/release.yml`  |
| `gitea`   | private Gitea| Gitea Maven registry| Proprietary     | `.gitea/workflows/release.yml`   |

Kind is autodetected from the target's `origin` remote (github → `clojars`,
else `gitea`); override with `--kind`.

## Publishability guard

A library is publishable only if every **runtime** `:deps` entry is
`:mvn/version`. `:git/tag` / `:git/sha` / `:local/root` runtime coords cannot
form a complete Maven pom, so `bb-build new` refuses a repo that carries them
outside its `:test` / `:dev` aliases.

## Options

- `--kind clojars|gitea` — template kind (default: autodetect).
- `--minor N` — `version.edn :minor` (default `1`).
- `--license NAME` / `--license-url URL` — override the kind's license default.
- `--force` — overwrite an existing `version.edn` / workflow.

## Existing files are never clobbered

Without `--force`, a `version.edn` or workflow that already exists is left
untouched and reported as `skipped-exists`. `bb-build` never edits `deps.edn` —
the `:build` alias is printed for you to paste, because a hand-maintained
`deps.edn` is not safe to rewrite automatically.
