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
        stage('Import Local Variables') {
            steps {
                script {
                    if (env.PIPELINE_LOCAL_VARIABLES) {
                        ARRAY_LOCAL_VARIABLE = PIPELINE_LOCAL_VARIABLES.trim().tokenize("\n")
                        ARRAY_LOCAL_VARIABLE.each {
                            def (env_name, env_value) = it.split('=', 2)
                            env."$env_name"="$env_value"
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                checkout changelog: true, \
                scm: [$class: 'GitSCM', \
                branches: [[name: 'master']], \
                gitTool: "${GIT_TOOL}", \
                extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false], [$class: 'CleanBeforeCheckout']], \
                userRemoteConfigs: [[url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]
                sh '''
                    git remote set-url origin --push ${GERRIT_CENTRAL}/${GIT_REPO_PATH}
                '''
                script {
                    // Read the initial version from the VERSION_PREFIX file
                    def versionContent = readFile('VERSION_PREFIX').trim()
                    // Set the VERSION variable as an environment variable
                    env.VERSION = versionContent
                    // Echo the value of VERSION for verification
                    echo "Initial version: $VERSION"
                    env.BRANCH_NAME="point_fix_$VERSION"
                    echo "BRANCH_NAME= $BRANCH_NAME"
               }
            }
        }
        stage('Inject Credential Files') {
            steps {
                withCredentials([file(credentialsId: 'lciadm100-docker-auth', variable: 'dockerConfig')]) {
                    sh "install -m 600 ${dockerConfig} ${HOME}/.docker/config.json"
                    sh "ls -la ${HOME}/.docker/"
                }
            }
        }
        stage("Check if branch exists") {
            steps {
                script {
                    env.exists = check_branch("${GIT_REPO_PATH}", "${env.BRANCH_NAME}")

                    echo "exists=${env.exists}"
                }
            }
        }
        stage("Create Branch") {
            when { expression { env.exists != "true" } }
            steps {
                script {
                    sh '''
                        echo ${WORKSPACE}
                        chmod -R 777 ${WORKSPACE}
                    '''
                    functionToCreatePointfixBranch("${GIT_REPO_PATH}", "${env.BRANCH_NAME}")
                }
            }
        }
        stage('Check if any updates') {
            when { expression { env.exists == "true" } }
            steps {
                script {
                    // Switch to the existing branch
                    sh(returnStatus: true, script: "git checkout ${env.BRANCH_NAME}")
                    def changed = checkIfVersionPrefixChanged()
                    echo "${changed}"

                    if(changed == false) {
                        println("*****************************************************************************************************************************************************************************************")
                        println("Remote branch ${env.BRANCH_NAME} exists but there is no change to VERSION_PREFIX. Hence calling function to bump patch version and push the changes to the remote repo...")
                        bumpPatchVersion("${GIT_REPO_PATH}", "${env.BRANCH_NAME}")
                        env.updated = "TRUE"
                        println("*****************************************************************************************************************************************************************************************")
                    } else {
                        println("******************************************************************************************************")
                        println("Remote branch ${env.BRANCH_NAME} exists and VERSION_PREFIX has been updated. Hence skipping this repo.")
                        currentBuild.description = "Branch already created. Skipping this repo"
                        println("******************************************************************************************************")
                        env.updated = "FALSE"
                    }

                }
            }
        }
    }
    post {
        always {
            script {
                sh "docker system prune --volumes --all --force" // Remove all unused containers, networks, images (both dangling and unreferenced), and volumes
            }
        }
        success {
            script {
                // Triggering another Jenkins job
                build job: 'eric-cniv-enm_CSAR_Release', wait: false
            }
        }
        failure {
            mail to: 'PDLAZELLES@pdl.internal.ericsson.com',
            from: "enmadm100@lmera.ericsson.se",
            subject: "Failed Pipeline: ${currentBuild.fullDisplayName}",
            body: "Failure on ${env.BUILD_URL}"
        }
    }
}

def check_branch(repo_check, branch_check) {
    return_value= false
    branch_exists = sh(returnStatus: true, script: """git ls-remote --exit-code --heads $env.GERRIT_MIRROR/$repo_check $branch_check""")

    if(branch_exists == 0){
        return_value= true
    }
    return return_value
}
def checkIfVersionPrefixChanged() {
    def status = sh (returnStatus: true, script: 'git diff --name-only  origin/master | grep VERSION_PREFIX')
    return status == 0
}
def functionToCreatePointfixBranch(repo_check, branch_check) {
    println("Remote branch $branch_check doesn't exist. Calling function to create branch, bump patch version and push the changes to the remote repo...")
    sh(returnStatus: true, script: "git checkout -b $branch_check")
    println("*******************************************************************************************************************************************************")
    bumpPatchVersion("$repo_check", "$branch_check")
    println("Pointfix Branch ${env.BRANCH_NAME} created for the repo: ${GIT_REPO_PATH}")
    println("*******************************************************************************************************************************************************")
    sh(returnStatus: true, script: "git checkout master")
    sh(returnStatus: true, script: "git branch -D $branch_check")
}
def bumpPatchVersion(repo_check, branch_check) {

    env.oldVersion = readFile "VERSION_PREFIX"
    env.oldVersion = env.oldVersion.trim()

    sh 'docker run --rm -v $PWD/VERSION_PREFIX:/app/VERSION -w /app armdocker.rnd.ericsson.se/proj-enm/bump patch'

    env.newVersion = readFile "VERSION_PREFIX"
    env.newVersion = env.newVersion.trim()
    env.IMAGE_VERSION = env.newVersion

    sh """
        git add VERSION_PREFIX
        git commit -m "[ci-skip] Automatic new patch version bumping: $IMAGE_VERSION"
        git push $env.GERRIT_CENTRAL/$repo_check $branch_check
    """
    status = "OK"
    env.updated = "TRUE"
    currentBuild.description = "Pointfix Branch Version: " + env.newVersion + "<BR>"
}