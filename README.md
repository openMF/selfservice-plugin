# Self service Plugin for Apache Fineract

## For Users


1. [Download link for Self Service Plugin ](https://sourceforge.net/projects/mifos/files/mifos-plugins/SelfServicePlugin/SelfServicePlugin-1.9.0.zip/download)  and extract the files (java jar files are on it)

1a. Execute only for DOCKER - Create a directory, copy the Self Service Plugin libraries in it

```bash
    mkdir fineract-selfservice  && cd fineract-selfservice
```

1b. Execute only for TOMCAT - Copy the Self Service Plugin and Pentaho libraries in $TOMCAT_HOME/webapps/fineract-provider/WEB-INF/lib/

5. Restart Docker or Tomcat

6. Test the Self Service

## For Developers

Add the repository https://mifos.jfrog.io/artifactory/libs-snapshot-local/

Maven
```bash
    <dependency>
        <groupId>community.mifos</groupId>
        <artifactId>selfservice-plugin</artifactId>
        <version>1.9.0-SNAPSHOT</version>
    </dependency>
```
Gradle
```bash
    compile(group: 'community.mifos', name: 'selfservice-plugin', version: '1.9.0-SNAPSHOT')
```

## Build & Use For Linux Users

This project is currently only tested against the very latest and greatest
bleeding edge Fineract `develop` branch on Linux Ubuntu 22.04LTS. Building and using it against
other versions may be possible, but is not tested or documented here.

1. Download and compile

```bash
    git clone https://github.com/openMF/selfservice-plugin.git
    cd selfservice-plugin && ./mvnw -Dmaven.test.skip=true clean package && cd ..
```

2. Execute Apache Fineract with the location of the Mifos Pentaho Plugin library for Apache Fineract

```bash
java -Dloader.path=$APACHE_FINERACT_PLUGIN_HOME/libs/ -jar $APACHE_FINERACT_HOME/fineract-provider.jar
```

## Contribute

If this Fineract plugin project is useful to you, please contribute back to it (and
Fineract) by raising Pull Requests yourself with any enhancements you make, and by helping
to maintain this project by helping other users on Issues and reviewing PR from others
(you will be promoted to committer on this project when you contribute).  We recommend
that you _Watch_ and _Star_ this project on GitHub to make it easy to get notified.

