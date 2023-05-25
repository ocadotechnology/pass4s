credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.4.2")
addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.4.21")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.7")
