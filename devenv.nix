{ pkgs, ... }:
let
  # Tool configuration lives in .config/ rather than the repository root.
  # Hooks pass these paths explicitly so they also work when committing
  # outside the devenv shell.
  cljfmtConfig = ".config/cljfmt.edn";
  cljKondoConfigDir = ".config/clj-kondo";
  valeConfig = ".config/vale.ini";
  gitlintConfig = ".config/gitlint";
  markdownlintConfig = ".config/markdownlint.yaml";
  typosConfig = ".config/typos.toml";
  lycheeConfig = ".config/lychee.toml";

  # Vendored upstream files stay byte-for-byte as released.
  vendored = [ "^\\.config/vale/Google/" ];
  # Generated reports, which the Markdown checks don't apply to.
  generated = [ "^docs/sample-github-summary\\.md$" ];
in
{
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk21;
  };
  languages.clojure.enable = true;

  packages = [
    pkgs.babashka
    pkgs.clj-kondo
    pkgs.cljfmt
    pkgs.gitlint
    pkgs.polylith
    pkgs.vale
  ];

  # So vale and gitlint find their config when run by hand.
  env = {
    VALE_CONFIG_PATH = valeConfig;
    GITLINT_CONFIG = gitlintConfig;
  };

  git-hooks.hooks = {
    cljfmt = {
      enable = true;
      entry = "${pkgs.cljfmt}/bin/cljfmt fix --config ${cljfmtConfig}";
    };

    # Dogfood: fail the commit on new error-level metric violations, comparing
    # the working tree with HEAD. Runs under babashka (~0.1 s).
    assay = {
      enable = true;
      name = "assay";
      description = "Check Clojure code metrics with assay.";
      entry = "${pkgs.babashka}/bin/bb assay --format text --base HEAD";
      pass_filenames = false;
      files = "^(components|bases)/[^/]+/src/.*\\.clj[cs]?$";
    };

    clj-kondo = {
      enable = true;
      name = "clj-kondo";
      description = "Lint Clojure code with clj-kondo.";
      entry = "${pkgs.clj-kondo}/bin/clj-kondo --config-dir ${cljKondoConfigDir} --lint";
      types_or = [
        "clojure"
        "clojurescript"
        "edn"
      ];
    };

    vale = {
      enable = true;
      settings.configPath = valeConfig;
      types = [ "markdown" ];
      excludes = generated;
    };

    gitlint = {
      enable = true;
      entry = "${pkgs.gitlint}/bin/gitlint --config ${gitlintConfig} --staged --msg-filename";
    };

    # Repository hygiene
    check-merge-conflicts.enable = true;
    detect-private-keys.enable = true;
    check-added-large-files.enable = true;
    check-symlinks.enable = true;
    check-executables-have-shebangs.enable = true;
    end-of-file-fixer = {
      enable = true;
      excludes = vendored ++ [
        # devenv writes it without a trailing newline.
        "^devenv\\.lock$"
      ];
    };
    trim-trailing-whitespace = {
      enable = true;
      excludes = vendored;
    };

    # Spelling in code, comments, and docstrings (vale covers Markdown prose)
    typos = {
      enable = true;
      # Set entry directly: the module ignores settings.configPath.
      entry = "${pkgs.typos}/bin/typos --config ${typosConfig} --force-exclude";
    };

    # Markdown structure and links
    markdownlint = {
      enable = true;
      entry = "${pkgs.markdownlint-cli}/bin/markdownlint --config ${markdownlintConfig}";
      excludes = generated;
    };
    lychee = {
      enable = true;
      settings.configPath = lycheeConfig;
      types = [ "markdown" ];
      excludes = generated;
    };

    # Nix
    nixfmt.enable = true;
    deadnix.enable = true;
    statix.enable = true;

    # Shell scripts and GitHub Actions, once there are any
    shellcheck.enable = true;
    actionlint.enable = true;
  };

  scripts = {
    test.exec = "clojure -M:dev:test \"$@\"";
    lint.exec = "clj-kondo --config-dir ${cljKondoConfigDir} --lint components bases development";
    fmt.exec = "cljfmt fix --config ${cljfmtConfig} components bases development";
    assay.exec = "clojure -M:dev:run \"$@\"";
  };

  enterTest = ''
    poly check
    clojure -M:dev:test
  '';
}
