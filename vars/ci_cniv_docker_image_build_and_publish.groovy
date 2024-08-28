#!/usr/bin/env groovy

def call(String stage) {
    if (stage == "build-image") {
        sh "${bob} image:docker-build"
    } else if (stage == "package-image") {
        sh "${bob} package-local:image-push-internal"
    } else if (stage == "publish-image") {
        sh "${bob} publish:image-pull-internal publish:image-tag-public publish:image-push-public"
    }

    def DOCKER_FILE_SUBDIR = sh(script: "find . -mindepth 2 -name \"Dockerfile*\" | tr \"\n\" \",\"", returnStdout: true)
    def dockerFileList = DOCKER_FILE_SUBDIR.split(",")
    dockerFileList.each {
        def DOCKER_FILE_SIDECAR_PATH = it.replaceFirst(~"^./", "").replaceAll("/Dockerfile", "")

        sh "echo ${DOCKER_FILE_SIDECAR_PATH} >.bob/var.docker-file-sidecar-path"

        if (DOCKER_FILE_SIDECAR_PATH) {
            if (stage == "build-image") {
                sh "${bob} image:docker-build-sidecars"
            } else if (stage == "package-image") {
                sh "${bob} package-local:image-push-internal-sidecars"
            } else if (stage == "publish-image") {
                sh "${bob} publish:image-pull-internal-sidecars publish:image-tag-public-sidecars publish:image-push-public-sidecars"
            }
        }
    }
}