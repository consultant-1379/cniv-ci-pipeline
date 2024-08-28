pipeline {
    agent {
        node {
            label "Cloud-Native"
        }
    }
    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '50', artifactNumToKeepStr: '50'))
    }
    stages {
        stage('Clean') {
            steps {
                deleteDir()
            }
        }
        stage('Checkout Git Repository') {
            steps {
                checkout changelog: true, \
                scm: [$class: 'GitSCM', \
                branches: [[name: '${BRANCH}']], \
                gitTool: "${GIT_TOOL}", \
                extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false], [$class: 'CleanBeforeCheckout']], \
                userRemoteConfigs: [[url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]

                sh '''
                    GERRIT_HOST=$(echo ${GERRIT_CENTRAL} | sed "s/ssh:\\/\\///;s/\\:29418$//")
                    scp -p -P 29418 ${GERRIT_HOST}:hooks/commit-msg .git/hooks/
                '''
            }
        }
        stage('Update Base OS') {
            steps {
                script {
                    sh """
                        git checkout -b ${BRANCH}
                        sed -i 's/CBO_VERSION=.*/CBO_VERSION=${IMAGE_TAG}/' Dockerfile
                        git add Dockerfile
                        git commit -m "NO JIRA Update Common Base OS to ${IMAGE_TAG}"
                        git push ${GERRIT_CENTRAL}/${GIT_REPO_PATH} HEAD:refs/for/${BRANCH}
                    """
                }
            }
        }
    }
    post {
        failure {
            mail to: 'PDLAZELLES@pdl.internal.ericsson.com',
            from: "enmadm100@lmera.ericsson.se",
            subject: "Failed Pipeline: ${currentBuild.fullDisplayName}",
            body: "Failure on ${env.BUILD_URL}"
        }
    }
}