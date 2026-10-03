import { render } from 'preact';
import '@fontsource/bebas-neue/400.css';
import '@fontsource-variable/manrope';
import './styles/app.css';
import { App } from './app';

render(<App />, document.getElementById('app')!);
