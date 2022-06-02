credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

resolvers += "Sonatype Nexus Repository Manager Releases" at "https://ocean.nexus.ocado.tech/repository/maven-releases/"
resolvers += "Sonatype Nexus Repository Manager Snapshots" at "https://ocean.nexus.ocado.tech/repository/maven-snapshots/"
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.15")
