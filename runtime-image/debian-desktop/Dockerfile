# AndCode "Debian desktop" runtime image (arm64).
#
# Baked at CI build time (see pr-apk-build.yml) from the same package set the installer would
# otherwise apt-get on the device, then embedded into the APK as an app asset so a fresh install
# extracts a fully-provisioned XFCE + TigerVNC environment without ever running apt - which is
# what failed on real devices with "dpkg was interrupted" / "status-old: Operation not permitted".
#
# Keep this recipe in sync with LocalRuntimeInstaller.kt:
#   REQUIRED_RUNTIME_PACKAGES + OPTIONAL_DEVELOPMENT_PACKAGES + DESKTOP_RUNTIME_PACKAGES + bsdutils
# (`gh` is not carried by Debian bookworm, so it is added from the GitHub CLI apt repo, exactly as
# installGitHubCli() does on device.)
FROM debian:12-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        bash git curl wget jq openssh-client ripgrep ca-certificates libstdc++6 \
        adb python3 python3-pil \
        tree file less nano vim openjdk-17-jdk-headless gradle python3-pip nodejs npm \
        make cmake gcc g++ libc6-dev pkg-config patch zip unzip sqlite3 golang-go util-linux \
        bsdutils \
        xfce4 xfce4-terminal dbus-x11 xauth tigervnc-standalone-server fonts-dejavu-core && \
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        -o /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=arm64 signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        > /etc/apt/sources.list.d/github-cli.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends gh && \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* /usr/share/man /usr/share/doc /usr/share/locale && \
    /usr/sbin/update-ca-certificates

# The installer configures resolv.conf, hosts, profile, the glibc loader and the VNC password /
# xstartup itself at install time (configureRootfs / provisionDesktop), so the exported rootfs is
# deliberately kept config-free: the on-device provisioning stays the single source of truth.
CMD ["/bin/bash"]