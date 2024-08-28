#!/usr/bin/env groovy

def call() {
    def supportTeamEmail = "PDLAZELLES@pdl.internal.ericsson.com"
    if (env.AUTOMATIC_EMAIL_LIST) {
        env.AUTOMATIC_EMAIL_LIST = "${supportTeamEmail},${AUTOMATIC_EMAIL_LIST}"
    } else {
        env.AUTOMATIC_EMAIL_LIST = supportTeamEmail
    }
    mail to: "${AUTOMATIC_EMAIL_LIST}",
    from: "enmadm100@lmera.ericsson.se",
    subject: "Failed CNIV Pipeline - Automatic Update: ${currentBuild.fullDisplayName}",
    body: "Failure on ${env.BUILD_URL}"
}
