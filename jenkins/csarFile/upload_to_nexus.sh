#!/bin/bash

version=$1
fileName=$2
repositoryUrl=$3
package=$4
packageType=$5

if [ -z ${version+x} ]; then
    echo "${package} version required"
    exit 1
fi

if [ -z ${fileName+x} ]; then
    echo "File name required with full path"
    exit 1
fi

if [ -z ${repositoryUrl+x} ]; then
    echo "Repository URL required"
    exit 1
fi

#list the contents of the dir. this will make it easier to troubleshoot issues with the build.
ls

#mvn command to upload the csar in nexus
mvn -V -B deploy:deploy-file -Durl=${repositoryUrl} -DrepositoryId=nexus -DgroupId=cENM.${packageType} -DartifactId=${package} -Dversion=${version} -DgeneratePom=true -Dpackaging=csar -Dfile=${fileName}
