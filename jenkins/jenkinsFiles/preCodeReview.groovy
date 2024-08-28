@Library('ci-cn-pipeline-lib') _

env.bob = new BobCommand()
    .needDockerSocket(true)
    .toString()

pipeline {
    agent {
        node {
            label 'VA'
        }
    }
    options {
        skipDefaultCheckout true
        timestamps()
        timeout(time: 10, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
    }
    stages {
        stage('Clean') {
            steps {
                deleteDir()
            }
        }
        stage('Prepare') {
            steps {
                checkout changelog: true, \
                scm: [$class: 'GitSCM', \
                branches: [[name: '${GERRIT_REFSPEC}']], \
                gitTool: "${GIT_TOOL}", \
                doGenerateSubmoduleConfigurations: false, \
                extensions: [[$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]], \
                submoduleCfg: [], \
                userRemoteConfigs: [[refspec: '${GERRIT_REFSPEC}', \
                url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]
            }
        }
        stage('Run CNIV Pipeline Rule') {
            steps {
                sh 'cp jenkins/cnivFiles/ruleset2.0.yaml .'
                sh "${bob} pre-code-review"
            }
        }
        stage('Run CNIV CSAR Rule') {
            steps {
                sh 'cp jenkins/csarFile/ruleset2.0.yaml .'
                sh "${bob} pre-code-review"
            }
        }
    }
    post {
        success {
            echo 'Pipeline Successfully Completed'
        }
        failure {
            emailext(attachLog: true,
                attachmentsPattern: 'currentBuild.rawBuild.log',
                from: 'enmadm100@lmera.ericsson.se',
                to: "${env.GERRIT_EVENT_ACCOUNT_EMAIL}",
                subject: "Failed: Jenkins Job ${env.JOB_NAME}",
                body: "Job: ${env.JOB_NAME}\nBuild Number: ${env.BUILD_NUMBER}\nThe Job build URL: ${env.BUILD_URL}")
        }
   }
}