import React from 'react';
import clsx from 'clsx';
import styles from './styles.module.css';

const FeatureList = [
  {
    title: 'Abstract',
    imagePath: "/pass4s/img/logo-medium.png",
    description: (
      <>
        Abstract away sender and consumer from implementation.
      </>
    ),
  },
  {
    title: 'Powered by Scala',
    imagePath: "/pass4s/img/scala.png",
    description: (
      <>
        Designed with functional Scala in mind.
      </>
    ),
  },
  {
    title: 'Ocado Technology',
    imagePath: "/pass4s/img/ocado.png",
    description: (
      <>
        Brought to you by <a href="https://github.com/ocadotechnology/">Ocado Technology</a>.
      </>
    ),
  },
];

function Feature({imagePath, title, description}) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center">
        <img src={imagePath} className={styles.featureSvg} />
      </div>
      <div className="text--center padding-horiz--md">
        <h3>{title}</h3>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures() {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
