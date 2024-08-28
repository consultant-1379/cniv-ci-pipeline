#!/usr/bin/env groovy

def call() {
    env.AUTOMATIC_REVIEW = false
    def COMMIT_MESSAGES_LIST = ["NO JIRA Update Common Base OS", "NO JIRA Version updated for"]
    for (def COMMIT_MESSAGE : COMMIT_MESSAGES_LIST) {
        if (env.GERRIT_CHANGE_SUBJECT.contains(COMMIT_MESSAGE)) {
            env.AUTOMATIC_REVIEW = true
            break
        }
    }
}
