## Dockerfile to build apk, fetching all necessary dependencies for that along the way
## Build/copy app-debug.apk to current dir with: docker build --output type=local,dest=. .
## For older docker where build != buildx, install/run: docker buildx build --output type=local,dest=. .
## IMPORTANT: Requires at least 6GB of RAM allocated to Docker (8GB recommended)
## On Apple Silicon (M1/M2), x86_64 emulation is used - increase Docker memory in settings

FROM --platform=linux/amd64 debian:stable-slim AS build

RUN echo >/apt '#!/bin/sh' && chmod +x /apt && echo >>/apt \
	'export DEBIAN_FRONTEND=noninteractive; exec apt-get </dev/null' \
	'-o=Dpkg::Options::=--force-confold -o=Dpkg::Options::=--force-confdef' \
	'--assume-yes --quiet --no-install-recommends "$@"'
RUN /apt update
RUN /apt install openjdk-21-jre-headless
RUN /apt install curl unzip

ENV HOME=/build/home UID=57839
RUN useradd -u $UID -d $HOME build \
	&& install -o build -g build -m700 -d ${HOME%/*} $HOME
WORKDIR $HOME
USER build

RUN file=commandlinetools-linux-11076708_latest.zip \
	&& curl --progress-bar -fLO https://dl.google.com/android/repository/$file \
	&& unzip $file && rm -f $file \
	&& mkdir android && mv cmdline-tools android/tools
RUN cd android && yes | ./tools/bin/sdkmanager --licenses --sdk_root=.

COPY --chown=build:build . /build/home/nfc-epaper-writer

RUN cd nfc-epaper-writer \
	&& ANDROID_HOME=$HOME/android \
	   GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=120000 -Dorg.gradle.internal.http.socketTimeout=120000 -Xmx4g" \
	   JAVA_TOOL_OPTIONS="-Xmx4g" \
	   bash gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process assembleDebug \
	&& cp app/build/outputs/apk/debug/app-debug.apk /build \
	&& rm -rf $HOME

FROM scratch AS artifact
COPY --from=build /build/app-debug.apk /
