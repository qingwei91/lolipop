/**
 * entry.js
 * 
 * This is the first file loaded. It sets up the Renderer, 
 * Scene and Camera. It also starts the render loop and 
 * handles window resizes.
 * 
 */

import * as THREE from 'three';

const canvas = document.querySelector('#c');
const renderer = new THREE.WebGLRenderer({antialias: true, canvas: canvas});

const fov = 75;
const aspect = 2;  // the canvas default
const near = 0.1;
const far = 5;
const camera = new THREE.PerspectiveCamera(fov, aspect, near, far);

camera.position.z = 2;

const scene = new THREE.Scene();

const boxWidth = 1;
const boxHeight = 1;
const boxDepth = 1;
const geometry = new THREE.BoxGeometry(boxWidth, boxHeight, boxDepth);

const material = new THREE.MeshPhongMaterial({color: 0x44aa88});

const cube = new THREE.Mesh(geometry, material);

scene.add(cube);

const color = 0xFFFFFF;
const intensity = 1;
const light = new THREE.DirectionalLight(color, intensity);
light.position.set(-1, 2, 4);

scene.add(light)

// camera
// camera.position.set(6,3,-10);
// camera.lookAt(new Vector3(0,0,0));

// renderer
// renderer.setPixelRatio(window.devicePixelRatio);
// renderer.setClearColor(0x7ec0ee, 1);

// render loop
const onAnimationFrameHandler = (time) => {
  time *= 0.001;  // convert time to seconds
  cube.rotation.x = time;
  cube.rotation.y = time;

  if (resizeRendererToDisplaySize(renderer)) {
    const canvas = renderer.domElement;
    camera.aspect = canvas.clientWidth / canvas.clientHeight;
    camera.updateProjectionMatrix();
  }
  renderer.render(scene, camera);
  window.requestAnimationFrame(onAnimationFrameHandler);
}

function resizeRendererToDisplaySize(renderer) {
  const canvas = renderer.domElement;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  const needResize = canvas.width !== width || canvas.height !== height;
  if (needResize) {
    renderer.setSize(width, height, false);
  }
  return needResize;
}


window.requestAnimationFrame(onAnimationFrameHandler);

// resize
// const windowResizeHanlder = () => {
//   const { innerHeight, innerWidth } = window;
//   renderer.setSize(innerWidth, innerHeight);
//   camera.aspect = innerWidth / innerHeight;
//   camera.updateProjectionMatrix();
// };
// windowResizeHanlder();
// window.addEventListener('resize', windowResizeHanlder);

// dom
// document.body.style.margin = 0;
// document.body.appendChild( renderer.domElement );

