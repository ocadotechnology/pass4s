"use strict";(self.webpackChunkpass4s=self.webpackChunkpass4s||[]).push([[586],{3905:(e,t,o)=>{o.d(t,{Zo:()=>c,kt:()=>f});var n=o(7294);function r(e,t,o){return t in e?Object.defineProperty(e,t,{value:o,enumerable:!0,configurable:!0,writable:!0}):e[t]=o,e}function s(e,t){var o=Object.keys(e);if(Object.getOwnPropertySymbols){var n=Object.getOwnPropertySymbols(e);t&&(n=n.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),o.push.apply(o,n)}return o}function a(e){for(var t=1;t<arguments.length;t++){var o=null!=arguments[t]?arguments[t]:{};t%2?s(Object(o),!0).forEach((function(t){r(e,t,o[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(o)):s(Object(o)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(o,t))}))}return e}function i(e,t){if(null==e)return{};var o,n,r=function(e,t){if(null==e)return{};var o,n,r={},s=Object.keys(e);for(n=0;n<s.length;n++)o=s[n],t.indexOf(o)>=0||(r[o]=e[o]);return r}(e,t);if(Object.getOwnPropertySymbols){var s=Object.getOwnPropertySymbols(e);for(n=0;n<s.length;n++)o=s[n],t.indexOf(o)>=0||Object.prototype.propertyIsEnumerable.call(e,o)&&(r[o]=e[o])}return r}var l=n.createContext({}),p=function(e){var t=n.useContext(l),o=t;return e&&(o="function"==typeof e?e(t):a(a({},t),e)),o},c=function(e){var t=p(e.components);return n.createElement(l.Provider,{value:t},e.children)},m="mdxType",d={inlineCode:"code",wrapper:function(e){var t=e.children;return n.createElement(n.Fragment,{},t)}},u=n.forwardRef((function(e,t){var o=e.components,r=e.mdxType,s=e.originalType,l=e.parentName,c=i(e,["components","mdxType","originalType","parentName"]),m=p(o),u=r,f=m["".concat(l,".").concat(u)]||m[u]||d[u]||s;return o?n.createElement(f,a(a({ref:t},c),{},{components:o})):n.createElement(f,a({ref:t},c))}));function f(e,t){var o=arguments,r=t&&t.mdxType;if("string"==typeof e||r){var s=o.length,a=new Array(s);a[0]=u;var i={};for(var l in t)hasOwnProperty.call(t,l)&&(i[l]=t[l]);i.originalType=e,i[m]="string"==typeof e?e:r,a[1]=i;for(var p=2;p<s;p++)a[p]=o[p];return n.createElement.apply(null,a)}return n.createElement.apply(null,o)}u.displayName="MDXCreateElement"},6252:(e,t,o)=>{o.r(t),o.d(t,{assets:()=>l,contentTitle:()=>a,default:()=>d,frontMatter:()=>s,metadata:()=>i,toc:()=>p});var n=o(7462),r=(o(7294),o(3905));const s={sidebar_position:4,description:"XML message serialization with Phobos"},a="XML",i={unversionedId:"modules/xml",id:"modules/xml",title:"XML",description:"XML message serialization with Phobos",source:"@site/../mdoc/target/mdoc/modules/xml.md",sourceDirName:"modules",slug:"/modules/xml",permalink:"/pass4s/docs/modules/xml",draft:!1,editUrl:"https://github.com/ocadotechnology/pass4s/edit/main/docs/modules/xml.md",tags:[],version:"current",sidebarPosition:4,frontMatter:{sidebar_position:4,description:"XML message serialization with Phobos"},sidebar:"sidebar",previous:{title:"JSON",permalink:"/pass4s/docs/modules/json"},next:{title:"Migration guide",permalink:"/pass4s/docs/migration-guide"}},l={},p=[],c={toc:p},m="wrapper";function d(e){let{components:t,...o}=e;return(0,r.kt)(m,(0,n.Z)({},c,o,{components:t,mdxType:"MDXLayout"}),(0,r.kt)("h1",{id:"xml"},"XML"),(0,r.kt)("p",null,"This section explains how to work with messages represented in XML. Pass4s comes with the ",(0,r.kt)("a",{parentName:"p",href:"https://github.com/Tinkoff/phobos/"},"Phobos")," support for XML message transformation."),(0,r.kt)("p",null,"To use the module make sure to add following import to your ",(0,r.kt)("inlineCode",{parentName:"p"},"build.sbt")),(0,r.kt)("pre",null,(0,r.kt)("code",{parentName:"pre",className:"language-scala"},'// phobos XML senders/consumers\n"com.ocadotechnology" %% "pass4s-phobos" % "v0.4.3"\n')),(0,r.kt)("p",null,"With that module added, you can now import additional syntax for consumers and producers. To enable the syntax add following import to your file:"),(0,r.kt)("pre",null,(0,r.kt)("code",{parentName:"pre",className:"language-scala"},"import com.ocadotechnology.pass4s.phobos.syntax._\n")),(0,r.kt)("p",null,"The syntax allows you to use ",(0,r.kt)("inlineCode",{parentName:"p"},".asXmlSender[T]")," on the senders and ",(0,r.kt)("inlineCode",{parentName:"p"},".asXmlConsumer[T]")," on consumers. Please note that for this to work, you need to provide ",(0,r.kt)("inlineCode",{parentName:"p"},"XmlEncoder[T]")," in case of ",(0,r.kt)("inlineCode",{parentName:"p"},"Sender")," and ",(0,r.kt)("inlineCode",{parentName:"p"},"XmlDecoder[T]")," for ",(0,r.kt)("inlineCode",{parentName:"p"},"Consumer"),". "),(0,r.kt)("p",null,"Here's how to create most basic encoder and decoder using Phobos:"),(0,r.kt)("pre",null,(0,r.kt)("code",{parentName:"pre",className:"language-scala"},'import ru.tinkoff.phobos.decoding._\nimport ru.tinkoff.phobos.encoding._\nimport ru.tinkoff.phobos.syntax._\nimport ru.tinkoff.phobos.derivation.semiauto._\n\nfinal case class XmlMessage(description: String, value: Long, rows: List[String])\n\nobject XmlMessage {\n  implicit val xmlEncoder: XmlEncoder[XmlMessage] = deriveXmlEncoder("xmlMessage")\n  implicit val xmlDecoder: XmlDecoder[XmlMessage] = deriveXmlDecoder("xmlMessage")\n}\n')),(0,r.kt)("p",null,"Please refer to the project repository ",(0,r.kt)("a",{parentName:"p",href:"https://github.com/Tinkoff/phobos"},"https://github.com/Tinkoff/phobos")," for more detailed guide on using Phobos."),(0,r.kt)("p",null,"When applying the syntax to consumer, consider using ",(0,r.kt)("a",{parentName:"p",href:"message-processor"},"MessageProcessor"),"."))}d.isMDXComponent=!0}}]);