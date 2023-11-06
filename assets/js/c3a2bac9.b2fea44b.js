"use strict";(self.webpackChunkpass4s=self.webpackChunkpass4s||[]).push([[779],{3905:(e,t,n)=>{n.d(t,{Zo:()=>p,kt:()=>g});var r=n(7294);function o(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function a(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);t&&(r=r.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),n.push.apply(n,r)}return n}function i(e){for(var t=1;t<arguments.length;t++){var n=null!=arguments[t]?arguments[t]:{};t%2?a(Object(n),!0).forEach((function(t){o(e,t,n[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):a(Object(n)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))}))}return e}function l(e,t){if(null==e)return{};var n,r,o=function(e,t){if(null==e)return{};var n,r,o={},a=Object.keys(e);for(r=0;r<a.length;r++)n=a[r],t.indexOf(n)>=0||(o[n]=e[n]);return o}(e,t);if(Object.getOwnPropertySymbols){var a=Object.getOwnPropertySymbols(e);for(r=0;r<a.length;r++)n=a[r],t.indexOf(n)>=0||Object.prototype.propertyIsEnumerable.call(e,n)&&(o[n]=e[n])}return o}var s=r.createContext({}),c=function(e){var t=r.useContext(s),n=t;return e&&(n="function"==typeof e?e(t):i(i({},t),e)),n},p=function(e){var t=c(e.components);return r.createElement(s.Provider,{value:t},e.children)},u="mdxType",d={inlineCode:"code",wrapper:function(e){var t=e.children;return r.createElement(r.Fragment,{},t)}},m=r.forwardRef((function(e,t){var n=e.components,o=e.mdxType,a=e.originalType,s=e.parentName,p=l(e,["components","mdxType","originalType","parentName"]),u=c(n),m=o,g=u["".concat(s,".").concat(m)]||u[m]||d[m]||a;return n?r.createElement(g,i(i({ref:t},p),{},{components:n})):r.createElement(g,i({ref:t},p))}));function g(e,t){var n=arguments,o=t&&t.mdxType;if("string"==typeof e||o){var a=n.length,i=new Array(a);i[0]=m;var l={};for(var s in t)hasOwnProperty.call(t,s)&&(l[s]=t[s]);l.originalType=e,l[u]="string"==typeof e?e:o,i[1]=l;for(var c=2;c<a;c++)i[c]=n[c];return r.createElement.apply(null,i)}return r.createElement.apply(null,n)}m.displayName="MDXCreateElement"},9270:(e,t,n)=>{n.r(t),n.d(t,{assets:()=>s,contentTitle:()=>i,default:()=>d,frontMatter:()=>a,metadata:()=>l,toc:()=>c});var r=n(7462),o=(n(7294),n(3905));const a={sidebar_position:5,description:"How to upgrade"},i="Migration guide",l={unversionedId:"migration-guide",id:"migration-guide",title:"Migration guide",description:"How to upgrade",source:"@site/../mdoc/target/mdoc/migration-guide.md",sourceDirName:".",slug:"/migration-guide",permalink:"/pass4s/docs/migration-guide",draft:!1,editUrl:"https://github.com/ocadotechnology/pass4s/edit/main/docs/migration-guide.md",tags:[],version:"current",sidebarPosition:5,frontMatter:{sidebar_position:5,description:"How to upgrade"},sidebar:"sidebar",previous:{title:"XML",permalink:"/pass4s/docs/modules/xml"},next:{title:"Localstack",permalink:"/pass4s/docs/localstack"}},s={},c=[{value:"0.4.0",id:"040",level:2},{value:"Intro",id:"intro",level:3},{value:"How to migrate",id:"how-to-migrate",level:3}],p={toc:c},u="wrapper";function d(e){let{components:t,...n}=e;return(0,o.kt)(u,(0,r.Z)({},p,n,{components:t,mdxType:"MDXLayout"}),(0,o.kt)("h1",{id:"migration-guide"},"Migration guide"),(0,o.kt)("h2",{id:"040"},"0.4.0"),(0,o.kt)("h3",{id:"intro"},"Intro"),(0,o.kt)("p",null,"In this version we fix compatibility with the Amazon\n",(0,o.kt)("a",{parentName:"p",href:"https://github.com/awslabs/amazon-sns-java-extended-client-lib"},"SNS"),"/",(0,o.kt)("a",{parentName:"p",href:"https://github.com/awslabs/amazon-sqs-java-extended-client-lib"},"SQS"),"\nExtended Client Library. The bug was that when uploading content to S3, the JSON format of the message sent to the\nbroker was incompatible with the Amazon's Extended Client."),(0,o.kt)("ul",null,(0,o.kt)("li",{parentName:"ul"},"Legacy format")),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-json"},'{"s3BucketName": "bucket", "s3Key": "key"}\n')),(0,o.kt)("ul",null,(0,o.kt)("li",{parentName:"ul"},"Correct format")),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-json"},'["software.amazon.payloadoffloading.PayloadS3Pointer", {"s3BucketName": "bucket", "s3Key": "key"}]\n')),(0,o.kt)("h3",{id:"how-to-migrate"},"How to migrate"),(0,o.kt)("ol",null,(0,o.kt)("li",{parentName:"ol"},"Deploy the new version of the library in all consumers (replace ",(0,o.kt)("inlineCode",{parentName:"li"},"usingS3Proxy")," with ",(0,o.kt)("inlineCode",{parentName:"li"},"usingS3ProxyForBigPayload"),").\nThe new consumer is compatible with both formats."),(0,o.kt)("li",{parentName:"ol"},"Deploy the new version of the library in all producers (replace ",(0,o.kt)("inlineCode",{parentName:"li"},"usingS3Proxy")," with ",(0,o.kt)("inlineCode",{parentName:"li"},"usingS3ProxyForBigPayload"),").\nThe new producer will send the message in the correct format."),(0,o.kt)("li",{parentName:"ol"},"If you are having scenario where an application is both consumer and producer, then for consuming follow point 1\nand for producing use firstly ",(0,o.kt)("inlineCode",{parentName:"li"},"usingS3ProxyLegacyEncoding")," and then in the next release migrate\nto ",(0,o.kt)("inlineCode",{parentName:"li"},"usingS3ProxyForBigPayload"))))}d.isMDXComponent=!0}}]);