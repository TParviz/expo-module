// Reexport the native module. On web, it will be resolved to FirstModule.web.ts
// and on native platforms to FirstModule.ts
export { default } from './src/FirstModule';
export * from './src/FirstModule.types';
export { default as FirstModuleView } from './src/FirstModuleView';
import FirstModule from './src/FirstModule';

export function showButton(text: string): string {
    return FirstModule.showButton(text);
}