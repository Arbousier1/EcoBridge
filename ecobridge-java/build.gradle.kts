name: EcoBridge Core Sync & Build

on:
  push:
    branches: [ "main" ]
  workflow_dispatch:

env:
  # 🔥 关键修复：在 CI 中禁用增量编译，这是解决 Windows 缓存失效的第一步
  CARGO_INCREMENTAL: 0
  # 强制 Cargo 使用更快的链接器（可选，但在 Windows 上有助于加速）
  RUSTFLAGS: "-C link-arg=/DEBUG:NONE" 

jobs:
  build-rust:
    name: Build Rust Core on ${{ matrix.os }}
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        include:
          - os: ubuntu-latest
            artifact_name: libecobridge_rust.so
          - os: windows-latest
            artifact_name: ecobridge_rust.dll
          - os: macos-latest
            artifact_name: libecobridge_rust.dylib

    steps:
      - uses: actions/checkout@v4

      - name: Setup Rust Toolchain
        uses: dtolnay/rust-toolchain@stable

      - name: Rust Cache
        uses: Swatinem/rust-cache@v2
        with:
          # 🔥 关键修复：显式指定工作区路径，并添加 OS 前缀防止 Key 冲突
          workspaces: "ecobridge-rust"
          prefix-key: "v1-rust-${{ matrix.os }}"

      - name: Build Rust Library (Release)
        # 强制使用 bash，防止 Windows 默认的 pwsh 处理路径出错
        shell: bash
        run: |
          cd ecobridge-rust
          cargo build --release

      - name: Prepare Artifact
        shell: bash
        run: |
          mkdir -p dist
          cp ecobridge-rust/ecobridge_rust.h dist/
          # Windows 的产物通常没有 'lib' 前缀，通过逻辑统一处理
          if [ "${{ matrix.os }}" = "windows-latest" ]; then
            cp ecobridge-rust/target/release/ecobridge_rust.dll dist/
          else
            cp ecobridge-rust/target/release/${{ matrix.artifact_name }} dist/
          fi

      - name: Upload Native Binary & Header
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.os }}-assets
          path: dist/

  build-java:
    name: Build Java Plugin (Java 25 + jextract)
    needs: build-rust
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'oracle' 

      - name: Install LLVM/Clang
        run: |
          sudo apt-get update
          sudo apt-get install -y libclang-dev clang

      - name: Download All Native Assets
        uses: actions/download-artifact@v4
        with:
          path: temp-assets
          merge-multiple: true

      - name: Sync Assets to Java Environment
        run: |
          mkdir -p ecobridge-java/src/main/resources/
          cp temp-assets/*.dll temp-assets/*.so temp-assets/*.dylib ecobridge-java/src/main/resources/
          mkdir -p ecobridge-rust/
          cp temp-assets/ecobridge_rust.h ecobridge-rust/

      - name: Setup jextract
        run: |
          wget https://download.java.net/java/early_access/jextract/22/3/openjdk-22-jextract+3-13_linux-x64_bin.tar.gz
          tar -xzf openjdk-22-jextract+3-13_linux-x64_bin.tar.gz
          echo "$(pwd)/jextract-22/bin" >> $GITHUB_PATH
          echo "JEXTRACT_HOME=$(pwd)/jextract-22" >> $GITHUB_ENV

      - name: Build with Gradle
        run: |
          cd ecobridge-java
          chmod +x gradlew
          # 之前修复的 generateBindings 逻辑会自动运行
          ./gradlew shadowJar
        env:
          ORG_GRADLE_PROJECT_version: ${{ github.ref_name }}

      - name: Upload Plugin JAR
        uses: actions/upload-artifact@v4
        with:
          name: EcoBridge-Plugin
          path: ecobridge-java/build/libs/*.jar