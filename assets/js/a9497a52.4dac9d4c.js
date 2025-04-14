"use strict";(self.webpackChunkpass4s=self.webpackChunkpass4s||[]).push([[25],{2848:(e,n,o)=>{o.r(n),o.d(n,{assets:()=>c,contentTitle:()=>a,default:()=>m,frontMatter:()=>s,metadata:()=>i,toc:()=>l});var t=o(8168),r=(o(6540),o(5680));const s={sidebar_position:2},a="Consumer",i={unversionedId:"core-concepts/Consumer",id:"core-concepts/Consumer",title:"Consumer",description:"Consumer is an abstraction for continuous process of executing logic upon receiving a message.",source:"@site/../mdoc/target/mdoc/core-concepts/Consumer.md",sourceDirName:"core-concepts",slug:"/core-concepts/Consumer",permalink:"/pass4s/docs/core-concepts/Consumer",draft:!1,editUrl:"https://github.com/ocadotechnology/pass4s/edit/main/docs/core-concepts/Consumer.md",tags:[],version:"current",sidebarPosition:2,frontMatter:{sidebar_position:2},sidebar:"sidebar",previous:{title:"Sender",permalink:"/pass4s/docs/core-concepts/Sender"},next:{title:"Broker",permalink:"/pass4s/docs/core-concepts/Broker"}},c={},l=[],p={toc:l},u="wrapper";function m(e){let{components:n,...o}=e;return(0,r.yg)(u,(0,t.A)({},p,o,{components:n,mdxType:"MDXLayout"}),(0,r.yg)("h1",{id:"consumer"},"Consumer"),(0,r.yg)("p",null,"Consumer is an abstraction for continuous process of executing logic upon receiving a message."),(0,r.yg)("p",null,"It's defined as a function in following shape: ",(0,r.yg)("inlineCode",{parentName:"p"},"(A => F[Unit]) => F[Unit]"),". This means a function that:"),(0,r.yg)("ul",null,(0,r.yg)("li",{parentName:"ul"},"takes an argument of type ",(0,r.yg)("inlineCode",{parentName:"li"},"A => F[Unit]")," - think of it as the processing logic type"),(0,r.yg)("li",{parentName:"ul"},"returns ",(0,r.yg)("inlineCode",{parentName:"li"},"F[Unit]")," means that consuming itself is only a side effect and yields no real value")),(0,r.yg)("pre",null,(0,r.yg)("code",{parentName:"pre",className:"language-scala"},"trait Consumer[F[_], +A] extends ((A => F[Unit]) => F[Unit]) with Serializable { self =>\n  /** Starts the consumer, passing every message through the processing function `f`. Think of it like of an `evalMap` on [[Stream]] or\n    * `use` on [[cats.effect.Resource]].\n    */\n  def consume(f: A => F[Unit]): F[Unit]\n\n  /** Starts the consumer, but allows the processing function `f` to be in a different effect than that of the consumer's. A `commit`\n    * function also needs to be passed - it will be used after every message.\n    */\n  def consumeCommit[T[_]](commit: T[Unit] => F[Unit])(f: A => T[Unit]): F[Unit] = self.consume(f andThen commit)\n}\n")),(0,r.yg)("p",null,"To start a consumer, you need a function that will handle messages of type ",(0,r.yg)("inlineCode",{parentName:"p"},"A")," and return effects in ",(0,r.yg)("inlineCode",{parentName:"p"},"F[_]"),". As you can see in the example above, the consumer can also be transactional, meaning it can perform an action in one effect and then translate the result to the other. It's especially useful when you want to perform database operations in ",(0,r.yg)("inlineCode",{parentName:"p"},"ConnectionIO[_]")," while your application effect is ",(0,r.yg)("inlineCode",{parentName:"p"},"IO[_]"),"."),(0,r.yg)("p",null,"The end user usually doesn't instantiate the ",(0,r.yg)("inlineCode",{parentName:"p"},"Consumer")," directly. Instead they would usually get one from ",(0,r.yg)("a",{parentName:"p",href:"broker"},(0,r.yg)("inlineCode",{parentName:"a"},"Broker"))," or ",(0,r.yg)("a",{parentName:"p",href:"../modules/message-processor"},(0,r.yg)("inlineCode",{parentName:"a"},"MessageProcessor")),"."),(0,r.yg)("p",null,"This abstraction comes with a lot of useful manipulators as well as ",(0,r.yg)("inlineCode",{parentName:"p"},"Semigroup"),", ",(0,r.yg)("inlineCode",{parentName:"p"},"Monoid"),", ",(0,r.yg)("inlineCode",{parentName:"p"},"Functor")," and ",(0,r.yg)("inlineCode",{parentName:"p"},"Monad")," instances. Please refer to ",(0,r.yg)("a",{parentName:"p",href:"https://github.com/ocadotechnology/pass4s/blob/main/kernel/src/main/scala/com/ocadotechnology/pass4s/kernel/Consumer.scala"},(0,r.yg)("inlineCode",{parentName:"a"},"Consumer.scala")," sources")," and the scaladocs."),(0,r.yg)("h1",{id:"basic-usage"},"Basic usage"),(0,r.yg)("p",null,"Here's a simple consumer implementation configured to use with our ",(0,r.yg)("a",{parentName:"p",href:"../localstack"},"localstack setup"),". If you want to run it locally, simply save the file somewhere and run it using ",(0,r.yg)("a",{parentName:"p",href:"https://scala-cli.virtuslab.org/install"},"scala-cli")," using ",(0,r.yg)("inlineCode",{parentName:"p"},"scala-cli run filename.scala")),(0,r.yg)("pre",null,(0,r.yg)("code",{parentName:"pre",className:"language-scala"},'//> using scala "2.13"\n//> using lib "com.ocadotechnology::pass4s-kernel:0.3.1"\n//> using lib "com.ocadotechnology::pass4s-core:0.3.1"\n//> using lib "com.ocadotechnology::pass4s-high:0.3.1"\n//> using lib "com.ocadotechnology::pass4s-connector-sqs:0.3.1"\n//> using lib "org.typelevel::log4cats-noop:2.5.0"\n\nimport cats.effect.ExitCode\nimport cats.effect.IO\nimport cats.effect.IOApp\nimport cats.implicits._\nimport com.ocadotechnology.pass4s.connectors.sqs.SqsConnector\nimport com.ocadotechnology.pass4s.connectors.sqs.SqsEndpoint\nimport com.ocadotechnology.pass4s.connectors.sqs.SqsSource\nimport com.ocadotechnology.pass4s.connectors.sqs.SqsUrl\nimport com.ocadotechnology.pass4s.core.Source\nimport com.ocadotechnology.pass4s.high.Broker\nimport org.typelevel.log4cats.Logger\nimport org.typelevel.log4cats.noop.NoOpLogger\nimport software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider\nimport software.amazon.awssdk.auth.credentials.AwsBasicCredentials\nimport software.amazon.awssdk.auth.credentials.AwsCredentialsProvider\nimport software.amazon.awssdk.auth.credentials.StaticCredentialsProvider\nimport software.amazon.awssdk.regions.Region\n\nimport java.net.URI\n\nobject BaseConsumer extends IOApp {\n\n  override def run(args: List[String]): IO[ExitCode] = {\n    implicit val ioLogger: Logger[IO] = NoOpLogger[IO]\n\n    // Initialize credentials\n    val awsCredentials = AwsBasicCredentials.create("test", "AWSSECRET")\n    val localstackURI = new URI("http://localhost:4566")\n    val sqsSource = SqsEndpoint(SqsUrl("http://localhost:4566/000000000000/local_queue"))\n\n    val credentialsProvider = StaticCredentialsProvider.create(awsCredentials)\n    // Create connector resource using provided credentials \n    val sqsConnector =\n      SqsConnector.usingLocalAwsWithDefaultAttributesProvider[IO](localstackURI, Region.EU_WEST_2, credentialsProvider)\n\n    sqsConnector.use { connector => // obtain the connector resource\n      val broker = Broker.fromConnector(connector) // create broker from connector\n\n      IO.println(s"Processor listening for messages on $sqsSource") *>\n        broker\n          .consumer(sqsSource) // obtain the consumer for certain SQS source\n          .consume(message => IO.println(s"Received message: $message")) // bind consumer logic\n          .background // run in background\n          .void\n          .use(_ => IO.never)\n    }\n  }\n}\n')),(0,r.yg)("p",null,"This is a rather raw way of using consumer, you might want to use ",(0,r.yg)("a",{parentName:"p",href:"../modules/message-processor"},"MessageProcessor")," for more elasticity and enriched syntax."))}m.isMDXComponent=!0},5680:(e,n,o)=>{o.d(n,{xA:()=>p,yg:()=>d});var t=o(6540);function r(e,n,o){return n in e?Object.defineProperty(e,n,{value:o,enumerable:!0,configurable:!0,writable:!0}):e[n]=o,e}function s(e,n){var o=Object.keys(e);if(Object.getOwnPropertySymbols){var t=Object.getOwnPropertySymbols(e);n&&(t=t.filter((function(n){return Object.getOwnPropertyDescriptor(e,n).enumerable}))),o.push.apply(o,t)}return o}function a(e){for(var n=1;n<arguments.length;n++){var o=null!=arguments[n]?arguments[n]:{};n%2?s(Object(o),!0).forEach((function(n){r(e,n,o[n])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(o)):s(Object(o)).forEach((function(n){Object.defineProperty(e,n,Object.getOwnPropertyDescriptor(o,n))}))}return e}function i(e,n){if(null==e)return{};var o,t,r=function(e,n){if(null==e)return{};var o,t,r={},s=Object.keys(e);for(t=0;t<s.length;t++)o=s[t],n.indexOf(o)>=0||(r[o]=e[o]);return r}(e,n);if(Object.getOwnPropertySymbols){var s=Object.getOwnPropertySymbols(e);for(t=0;t<s.length;t++)o=s[t],n.indexOf(o)>=0||Object.prototype.propertyIsEnumerable.call(e,o)&&(r[o]=e[o])}return r}var c=t.createContext({}),l=function(e){var n=t.useContext(c),o=n;return e&&(o="function"==typeof e?e(n):a(a({},n),e)),o},p=function(e){var n=l(e.components);return t.createElement(c.Provider,{value:n},e.children)},u="mdxType",m={inlineCode:"code",wrapper:function(e){var n=e.children;return t.createElement(t.Fragment,{},n)}},g=t.forwardRef((function(e,n){var o=e.components,r=e.mdxType,s=e.originalType,c=e.parentName,p=i(e,["components","mdxType","originalType","parentName"]),u=l(o),g=r,d=u["".concat(c,".").concat(g)]||u[g]||m[g]||s;return o?t.createElement(d,a(a({ref:n},p),{},{components:o})):t.createElement(d,a({ref:n},p))}));function d(e,n){var o=arguments,r=n&&n.mdxType;if("string"==typeof e||r){var s=o.length,a=new Array(s);a[0]=g;var i={};for(var c in n)hasOwnProperty.call(n,c)&&(i[c]=n[c]);i.originalType=e,i[u]="string"==typeof e?e:r,a[1]=i;for(var l=2;l<s;l++)a[l]=o[l];return t.createElement.apply(null,a)}return t.createElement.apply(null,o)}g.displayName="MDXCreateElement"}}]);