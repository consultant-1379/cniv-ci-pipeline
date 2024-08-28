import java.io.File
import groovy.json.JsonSlurper

project_file_path  = 'benchmarks'
String projects_file = readFileFromWorkspace(project_file_path)

projects_file.eachLine {
    project_details ->
        def env_map = [:] // hashmap to group environment variables contained in projects file

        project_details.tokenize(';').each {
            environment_variable ->
                key = environment_variable.split('=')[0]
                value = environment_variable.split('=')[1]
                env_map[key] = value
        }
        createPipelineJob(env_map)
}

def createPipelineJob(env_map) {
    git_repo_path = env_map['GIT_REPO_PATH']
    branch = env_map['BRANCH']
    pipeline_name = git_repo_path.split('/').last() + '_Drop'

    pipelineJob(pipeline_name) {
        description('<a href="https://eteamspace.internal.ericsson.com/display/DGBase/CNIV+Pipeline+-+User+Guide">CNIV Pipeline Documentation</a>')

        logRotator {
            numToKeep(20)
        }

        properties {
            disableConcurrentBuilds()
        }

        environmentVariables {
            env_map.each { key, value ->
                env(key, value)
            }
            env('GIT_HOME' , '/proj/ciexadm200/tools/git/2.28.0')
            env('GIT_TOOL' , 'Git 2.28.0')
            env('PATH+GIT' , '${GIT_HOME}/bin')
        }

        triggers {
            gerritTrigger {
                triggerOnEvents {
                    changeMerged()
                }

                // Specify what Gerrit project(s) to trigger a build on.
                gerritProjects {
                    gerritProject {
                        compareType("PLAIN") // The exact repository name in Gerrit, case sensitive equality.
                        pattern(git_repo_path)
                        branches {
                            branch {
                                compareType("PLAIN") // The exact branch name in Gerrit, case sensitive equality.
                                    pattern(branch)
                            }
                        }
                        disableStrictForbiddenFileVerification(false)
                    }
                }
            }
        }

        definition {
            cps {
                script(readFileFromWorkspace('jenkins/jenkinsFiles/jenkinsfile-cniv-drop.groovy'))
                sandbox(true)
            }
        }
    }
}

// Creates or updates a view that shows items in a simple list format.
listView('CNIV-Drop') {
    filterBuildQueue()
    filterExecutors()
    jobs {
        regex(/.*Drop/)
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