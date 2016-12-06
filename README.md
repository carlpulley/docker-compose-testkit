# Docker Compose TestKit

TODO:

| Service              | Status |
|----------------------|--------|
| Build                | [![Build Status](https://secure.travis-ci.org/carlpulley/docker-compose-testkit.png?tag=develop)](http://travis-ci.org/carlpulley/docker-compose-testkit) |
| Release              | [![Maven Central](https://img.shields.io/badge/maven--central-v0.0.4-blue.svg)](https://oss.sonatype.org/service/local/repositories/releases/content/net/cakesolutions/docker-compose-testkit_2.11/0.0.4/docker-compose-testkit_2.11-0.0.4.pom) |
| License              | [![Apache 2](https://img.shields.io/hexpm/l/plug.svg?maxAge=2592000)](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| API Documentation    | [![API](https://readthedocs.org/projects/pip/badge/)](https://carlpulley.github.io/docker-compose-testkit/latest/api) |
| Library Dependencies | [![Dependencies](https://app.updateimpact.com/badge/759750315422650368/docker-compose-testkit.svg?config=compile)](https://app.updateimpact.com/latest/759750315422650368/docker-compose-testkit) |
| Code Quality         | [![Codacy Badge](https://api.codacy.com/project/badge/Grade/74f48976fc564464b951d7dc817a33c9)](https://www.codacy.com/app/c-pulley/docker-compose-testkit) |
| Test Coverage        | [![Codacy Badge](https://api.codacy.com/project/badge/Coverage/74f48976fc564464b951d7dc817a33c9)](https://www.codacy.com/app/c-pulley/docker-compose-testkit) |

## Setup and Usage

To use this library, add the following dependency to your `build.sbt`
file:
```
libraryDependencies += "net.cakesolutions" %% "docker-compose-testkit" % "0.0.4"
```

TODO:

## Development Notes

### Building Locally from Source

```
sbt clean dockerCompose/publish-local dockerComposeTemplates/publish-local
sbt clean docker:publish-local
```

### Publishing API Documentation

```
sbt clean ghpagesPushSite
```

### Publishing Sonatype Artefacts

```
sbt clean dockerCompose/publishSigned dockerComposeTemplates/publishSigned sonatypeRelease
```
