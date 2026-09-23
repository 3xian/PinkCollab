# PinkCollab

This package installs the prebuilt [PinkCollab Gateway](https://github.com/3xian/PinkCollab) for the current operating system and CPU architecture. A Rust toolchain is not required.

```sh
npm install -g pinkcollab
pinkcollab init --workspace /absolute/path/to/projects
pinkcollab status
pinkcollab serve
```

Oh My Pi (OMP) remains a separate dependency; `omp --version` must work before the Gateway can run sessions.

Supported targets are macOS arm64/x64, Linux arm64/x64, and Windows x64. The platform binary is delivered through an optional `@pinkcollab/gateway-*` dependency selected by npm.
