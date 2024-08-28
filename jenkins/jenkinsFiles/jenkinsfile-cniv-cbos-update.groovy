pipeline {
    agent {
        node {
            label "Cloud-Native"
        }
    }
    parameters {
        string(name: 'IMAGE_TAG', defaultValue: '', description: 'The image tag for base OS (e.g. 1.0.0-7)')
    }
    stages {
        stage('Check Parameters') {
            steps {
                script {
                    if (params.IMAGE_TAG.empty) {
                        error "IMAGE_TAG parameter is required"
                    }
                }
            }
        }
        stage('Read and Trigger Jobs') {
            steps {
                script {
                    def reposToUpdate = readJSON file: "${WORKSPACE}/jenkins/cnivFiles/cniv_repos.json"
                    def jobSteps = [:]
                    reposToUpdate.repos.findAll { repo -> repo.cbos == "true" }.each { repo ->
                        def gitRepoPath = repo.gitRepo
                        def jobName = gitRepoPath.tokenize('/').last() + '-cbos-update'
                        jobSteps[jobName] = {
                            build job: jobName, parameters: [string(name: 'IMAGE_TAG', value: params.IMAGE_TAG)]
                        }
                    }
                    parallel jobSteps
                }
            }
        }
    }
    post {
        failure {
            echo "Pipeline failed: ${currentBuild.rawBuild.result}"
            mail to: 'PDLAZELLES@pdl.internal.ericsson.com',
                 from: "enmadm100@lmera.ericsson.se",
                 subject: "Failed Pipeline: ${currentBuild.fullDisplayName}",
                 body: "Failure on ${env.BUILD_URL}"
        }
    }
}