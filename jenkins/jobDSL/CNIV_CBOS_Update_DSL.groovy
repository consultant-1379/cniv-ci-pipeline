import groovy.json.JsonSlurper
def reposFilePath = "${WORKSPACE}/jenkins/cnivFiles/cniv_repos.json"
def reposJson = new JsonSlurper().parse(new File(reposFilePath))
def reposList = reposJson.repos.findAll { it.cbos == "true" }
reposList.each { repo ->
    def git_repo_path = repo.gitRepo
    def branch = repo.branch
    def pipeline_name = git_repo_path.tokenize('/').last() + '-cbos-update'
    pipelineJob(pipeline_name) {
        description('<a href="https://eteamspace.internal.ericsson.com/display/DGBase/CNIV+Pipeline+-+User+Guide">CNIV Pipeline Documentation</a>')
        parameters {
            stringParam('IMAGE_TAG', '', 'The image tag for base OS (e.g. 1.0.0-7)')
        }
        logRotator {
            numToKeep(20)
        }
        properties {
            disableConcurrentBuilds()
        }
        environmentVariables {
            env('GIT_REPO_PATH', git_repo_path)
            env('BRANCH', branch)
            env('GIT_HOME' , '/proj/ciexadm200/tools/git/2.28.0')
            env('GIT_TOOL' , 'Git 2.28.0')
            env('PATH+GIT' , '${GIT_HOME}/bin')
        }
        definition {
            cps {
                script(readFileFromWorkspace('jenkins/jenkinsFiles/jenkinsfile-cniv-listener.groovy'))
                sandbox(true)
            }
        }
    }
}
listView('CNIV_CBOS_UPDATER') {
    filterBuildQueue()
    filterExecutors()
    jobs {
        regex(/.*-cbos-update/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}