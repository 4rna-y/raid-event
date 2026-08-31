{
  description = "RaidEvent - Minecraft Paper plugin development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs:
        let
          # Paper 26.x (Minecraft 26.1 以降) は Java 25 以上が必須。
          # 1.21 系をターゲットにする場合も javac の --release 21 でビルドできる。
          jdk = pkgs.jdk25;
          # Gradle 8 系は Java 25 を release ターゲットとして受け付けない。
          gradle = pkgs.gradle_9;

          # Paper の jar を取得してパスを stdout に出す。進捗は stderr へ。
          # PaperMC の v2 API は廃止済みなので v3 (fill.papermc.io) を使う。
          paper-jar = pkgs.writeShellApplication {
            name = "paper-jar";
            runtimeInputs = [ pkgs.curl pkgs.jq pkgs.coreutils ];
            text = ''
              MC_VERSION="''${1:-}"
              RUN_DIR="''${PAPER_RUN_DIR:-./run}"
              API="https://fill.papermc.io/v3/projects/paper"
              UA="raid-event/dev (local test server)"

              # 未指定なら pre/rc を除いた最新バージョンを選ぶ
              if [ -z "$MC_VERSION" ]; then
                MC_VERSION="$(curl -fsSL -H "User-Agent: $UA" "$API" \
                  | jq -r '[.versions[][]] | map(select(contains("-") | not)) | .[0]')"
                echo "==> latest stable version: $MC_VERSION" >&2
              fi

              BUILD="$(curl -fsSL -H "User-Agent: $UA" "$API/versions/$MC_VERSION/builds" \
                | jq -r '[.[] | select(.channel == "STABLE")][0].downloads["server:default"]
                         | "\(.name)\t\(.url)\t\(.checksums.sha256)"')"
              if [ -z "$BUILD" ] || [ "$BUILD" = "null" ]; then
                echo "no stable build found for $MC_VERSION" >&2
                exit 1
              fi

              JAR="$(printf '%s' "$BUILD" | cut -f1)"
              URL="$(printf '%s' "$BUILD" | cut -f2)"
              SHA="$(printf '%s' "$BUILD" | cut -f3)"

              mkdir -p "$RUN_DIR"
              if [ ! -f "$RUN_DIR/$JAR" ]; then
                echo "==> downloading $JAR" >&2
                curl -fSL -H "User-Agent: $UA" -o "$RUN_DIR/$JAR.tmp" "$URL"
                echo "$SHA  $RUN_DIR/$JAR.tmp" | sha256sum -c - >&2
                mv "$RUN_DIR/$JAR.tmp" "$RUN_DIR/$JAR"
              fi

              printf '%s\n' "$RUN_DIR/$JAR"
            '';
          };

          # 単体の Paper サーバーを起動する。再起動は行わない。
          paper-server = pkgs.writeShellApplication {
            name = "paper-server";
            runtimeInputs = [ jdk paper-jar pkgs.coreutils ];
            text = ''
              RUN_DIR="''${PAPER_RUN_DIR:-./run}"
              JAR="$(paper-jar "$@")"

              # ローカル検証専用サーバーなので EULA は自動同意する。
              # 同意したくない場合はこの行を消して手動で eula.txt を編集すること。
              echo "eula=true" > "$RUN_DIR/eula.txt"

              cd "$RUN_DIR"
              exec java -Xms1G -Xmx2G -jar "$(basename "$JAR")" nogui
            '';
          };

          # ビルド成果物をテストサーバーの plugins/ に配置する。
          deploy-plugin = pkgs.writeShellApplication {
            name = "deploy-plugin";
            runtimeInputs = [ pkgs.findutils ];
            text = ''
              RUN_DIR="''${PAPER_RUN_DIR:-./run}"
              mkdir -p "$RUN_DIR/plugins"

              found=0
              for dir in plugin/build/libs build/libs; do
                [ -d "$dir" ] || continue
                while IFS= read -r -d ''' jar; do
                  cp -v "$jar" "$RUN_DIR/plugins/"
                  found=1
                done < <(find "$dir" -maxdepth 1 -name '*.jar' \
                  ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print0)
              done

              if [ "$found" -eq 0 ]; then
                echo "no plugin jar found in plugin/build/libs -- build first" >&2
                exit 1
              fi
            '';
          };

          # 開発用のワンショット: ビルド → 配置 → テストサーバー起動。
          # リポジトリのルートで実行すること。
          raidevent-dev = pkgs.writeShellApplication {
            name = "raidevent-dev";
            runtimeInputs = [ jdk gradle paper-jar paper-server deploy-plugin pkgs.coreutils ];
            text = ''
              echo "==> building" >&2
              gradle --console=plain -q :plugin:build
              deploy-plugin

              exec paper-server "$@"
            '';
          };
        in
        {
          default = pkgs.mkShell {
            packages = [
              jdk
              gradle
              pkgs.maven
              pkgs.jdt-language-server
              pkgs.curl
              pkgs.jq
              paper-jar
              paper-server
              deploy-plugin
              raidevent-dev
            ];

            JAVA_HOME = "${jdk}";

            shellHook = ''
              echo "RaidEvent dev shell"
              echo "  java          : $(java -version 2>&1 | head -n1)"
              echo "  gradle        : $(gradle --version 2>/dev/null | awk '/^Gradle/ {print $2}')"
              echo ""
              echo "  raidevent-dev [mc-version]  # ビルド → 配置 → ./run で起動"
              echo "  paper-server [mc-version]  # ./run にテストサーバーを起動"
              echo "  paper-jar [mc-version]     # jar を取得してパスを表示"
              echo "  deploy-plugin              # ビルド済み jar を ./run/plugins へコピー"
            '';
          };
        });

      formatter = forAllSystems (pkgs: pkgs.nixpkgs-fmt);
    };
}
