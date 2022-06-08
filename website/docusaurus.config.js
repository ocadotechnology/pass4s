// @ts-check
// Note: type annotations allow type checking and IDEs autocompletion

const lightCodeTheme = require('prism-react-renderer/themes/github');
const darkCodeTheme = require('prism-react-renderer/themes/dracula');

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Pass4s',
  tagline: ' Scala library providing an abstract layer for cross app messaging. ',
  url: 'https://ocadotechnology.github.io',
  baseUrl: '/pass4s/',
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',
  favicon: 'img/logo.png',

  organizationName: 'ocadotechnology', // Usually your GitHub org/user name.
  projectName: 'pass4s', // Usually your repo name.
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      '@docusaurus/preset-classic',
      {
        docs: {
          path: "../mdoc/target/mdoc",
          sidebarPath: require.resolve('./sidebars.js'),
          editUrl: ({locale, versionDocsDirPath, docPath}) => {
            return `https://github.com/ocadotechnology/pass4s/edit/main/docs/${docPath}`;
          }
        },
        blog: {
          showReadingTime: true,
          editUrl:
            'https://github.com/ocadotechnology/pass4s/edit/main/website/blog/',
        },
        theme: {
          customCss: require.resolve('./src/css/custom.css'),
        },
      },
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      navbar: {
        title: 'Pass4s',
        logo: {
          alt: 'Pass4s Logo',
          src: 'img/logo-medium.png',
        },
        items: [
          {
            type: 'doc',
            docId: 'getting-started',
            position: 'left',
            label: 'Documentation',
          },
          {
            href: 'https://github.com/ocadotechnology/pass4s',
            label: 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Docs',
            items: [
              {
                label: 'Documentation',
                to: '/docs/getting-started',
              }
              // TODO - add more pages here
            ],
          },
          // {
          //   title: 'Community',
          //   items: [
          //     {
          //       label: 'Stack Overflow',
          //       href: 'https://stackoverflow.com/questions/tagged/docusaurus',
          //     },
          //     {
          //       label: 'Discord',
          //       href: 'https://discordapp.com/invite/docusaurus',
          //     },
          //     {
          //       label: 'Twitter',
          //       href: 'https://twitter.com/docusaurus',
          //     },
          //   ],
          // },
          {
            title: 'More',
            items: [
              {
                label: 'Project on GitHub',
                href: 'https://github.com/ocadotechnology/sttp-oauth2',
              },
              {
                label: 'Ocado Technology on GitHub',
                href: 'https://github.com/ocadotechnology',
              },
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} Ocado Technology`,
      },
      // prism: {
      //   theme: lightCodeTheme,
      //   darkTheme: darkCodeTheme,
      // },
      prism: {
        // Java is here due to https://github.com/facebook/docusaurus/issues/4799
        additionalLanguages: ['java', 'scala'],
        theme: require('prism-react-renderer/themes/vsDark')
      },
    }),
};

module.exports = config;
