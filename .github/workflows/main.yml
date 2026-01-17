name: EcoBridge Core Sync & Build

on:
  push:
    branches: [ "main" ]
  workflow_dispatch:

env:
  # 全局禁用增量编译，这对所有 OS 的 CI 缓存稳定性都有好处
  CARGO_INCREMENTAL: 0

jobs:
  build-rust:
    name: Build Rust Core on ${{ matrix.os }}
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        include:
          - os: ubuntu-latest
            artifact_name: libecobridge_rust.so
            rust_flags: "" # Linux 不需要额外链接参数
          - os: windows-latest
            artifact_name: ecobridge_rust.dll
            # 🔥 仅为 Windows 分配 MSVC 专用优化参数
            rust_flags: "-C link-arg=/DEBUG:NONE"
          - os: macos-latest
            artifact_name: libecobridge_rust.dylib
            rust_flags: "" # macOS 不需要

    steps:
      - uses: actions/checkout@v4

      - name: Setup Rust Toolchain
        uses: dtolnay/rust-toolchain@stable

      - name: Rust Cache
        uses: Swatinem/rust-cache@v2
        with:
          workspaces: "ecobridge-rust"
          # 提升前缀版本以清理旧的、错误的缓存数据
          prefix-key: "v2-rust-${{ matrix.os }}"

      - name: Build Rust Library (Release)
        shell: bash
        run: |
          cd ecobridge-rust
          # 动态注入当前 OS 对应的参数
          export RUSTFLAGS="${{ matrix.rust_flags }}"
          cargo build --release

      - name: Prepare Artifact
        shell: bash
        run: |
          mkdir -p dist
          # 必须带上头文件，否则 Java 端的 jextract 没法生成代码
          cp ecobridge-rust/ecobridge_rust.h dist/
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
          # 1. 移动二进制库到 resources
          mkdir -p ecobridge-java/src/main/resources/
          cp temp-assets/*.dll temp-assets/*.so temp-assets/*.dylib ecobridge-java/src/main/resources/
          
          # 2. 恢复头文件，供 jextract 扫描
          mkdir -p ecobridge-rust/
          cp temp-assets/ecobridge_rust.h ecobridge-rust/

      - name: Setup jextract
        run: |
          # 下载适用于 Linux 的 jextract
          wget https://download.java.net/java/early_access/jextract/22/3/openjdk-22-jextract+3-13_linux-x64_bin.tar.gz
          tar -xzf openjdk-22-jextract+3-13_linux-x64_bin.tar.gz
          echo "$(pwd)/jextract-22/bin" >> $GITHUB_PATH
          echo "JEXTRACT_HOME=$(pwd)/jextract-22" >> $GITHUB_ENV

      - name: Build with Gradle
        run: |
          cd ecobridge-java
          chmod +x gradlew
          # 这里会自动运行之前修复的 generateBindings 任务
          ./gradlew shadowJar
        env:
          ORG_GRADLE_PROJECT_version: ${{ github.ref_name }}

      - name: Upload Plugin JAR
        uses: actions/upload-artifact@v4
        with:
          name: EcoBridge-Plugin
          path: ecobridge-java/build/libs/*.jar