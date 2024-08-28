#!/usr/bin/env groovy
def call() {
    def retry = 0
    def maxRetries = 40
    def sleepTime = 60
    while (retry < maxRetries) {
        retry++
        echo "INFO: Attempting retry #$retry of $maxRetries in $sleepTime seconds."
        centralCommitId = sh(script: "git ls-remote -h ${env.GERRIT_CENTRAL}/${env.GIT_REPO_PATH} master | awk '{print \$1}'", returnStdout: true).trim()
        mirrorCommitId = sh(script: "git ls-remote -h ${env.GERRIT_MIRROR}/${env.GIT_REPO_PATH} master | awk '{print \$1}'", returnStdout: true).trim()
        echo "INFO: Central commit ID: $centralCommitId"
        echo "INFO: Mirror commit ID: $mirrorCommitId"
        if (centralCommitId == mirrorCommitId) {
            echo "INFO: Gerrit central and mirror are in sync."
            return
        } else {
            echo "INFO: Gerrit central and mirror are out of sync."
            if (retry == maxRetries) {
                error "Gerrit mirror not in sync with central after $maxRetries retries."
            } else {
                echo "INFO: Waiting for sync..."
                sleep sleepTime
            }
        }
    }
}