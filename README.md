# Application for control the TV LG-24LJ4540.
The application (app) is part of the TV LG-24LJ4540 Remote Control System.<br>
The app works on a smartphone without Infrared port (IR) with the Android operating system.<br>
When a button is pressed on a smartphone, the application translates it into a command code,<br>
which is transmitted via the Bluetooth Low Energy (BLE)  protocol to the hardware module.<br>
The app is a BLE central device.<br>
To control the TV, a [hardware module](https://github.com/osnatos/TV_LG-24LJ4540_CC2340R5_peripheral) is used,<br>
 which converts command codes received via the BLE interface from the smartphone into IR control commands of TV.<br>
The hardware module is used as a peripheral device.<br>

