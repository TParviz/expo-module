import { useState } from 'react';
import { Button, Text, View } from "react-native";
import { showButton } from '../modules/first-module';

export default function Index() {
  const [result, setResult] = useState('');

  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
      <Button 
        title="Test Button" 
        onPress={() => setResult(showButton('Hello!'))}
      />
      <Text>"TEST"</Text>
    </View>
  );
}
