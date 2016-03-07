package com.polidea.rxandroidble
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.polidea.rxandroidble.exceptions.BleGattException
import com.polidea.rxandroidble.exceptions.BleGattOperationType
import rx.Observable
import rx.observers.TestSubscriber
import rx.subjects.PublishSubject
import spock.lang.Specification
import spock.lang.Unroll

import static com.polidea.rxandroidble.RxBleConnection.RxBleConnectionState.*

public class RxBleDeviceTest extends Specification {

    BluetoothDevice mockBluetoothDevice = Mock BluetoothDevice

    RxBleConnection.Connector mockConnector = Mock RxBleConnection.Connector

    PublishSubject<RxBleConnection> mockConnectorEstablishConnectionPublishSubject = PublishSubject.create()

    RxBleConnection mockConnection = Mock RxBleConnection

    PublishSubject<RxBleConnection.RxBleConnectionState> connectionStatePublishSubject = PublishSubject.create()

    RxBleDevice rxBleDevice = new RxBleDeviceImpl(mockBluetoothDevice, mockConnector)

    TestSubscriber deviceConnectionStateSubscriber = new TestSubscriber()

    def setup() {
        mockConnector.prepareConnection(_, _) >> mockConnectorEstablishConnectionPublishSubject
        mockConnection.getConnectionState() >> connectionStatePublishSubject
    }

    def "should return the BluetoothDevice name"() {

        given:
        mockBluetoothDevice.name >> "testName"

        expect:
        rxBleDevice.getName() == "testName"
    }

    def "should return the BluetoothDevice address"() {

        given:
        mockBluetoothDevice.address >> "aa:aa:aa:aa:aa:aa"

        expect:
        rxBleDevice.getMacAddress() == "aa:aa:aa:aa:aa:aa"
    }

    def "equals() should return true when compared to a different RxBleDevice instance with the same underlying BluetoothDevice"() {

        given:
        def differentRxBleDeviceWithSameBluetoothDevice = new RxBleDeviceImpl(mockBluetoothDevice, null)

        expect:
        rxBleDevice.equals(differentRxBleDeviceWithSameBluetoothDevice)
    }

    def "hashCode() should return the same value as a different RxBleDevice instance hashCode() with the same underlying BluetoothDevice"() {

        given:
        def differentRxBleDevice = new RxBleDeviceImpl(mockBluetoothDevice, null)

        expect:
        rxBleDevice.hashCode() == differentRxBleDevice.hashCode()
    }

    @Unroll
    def "establishConnection() should call RxBleConnection.Connector.prepareConnection() #id"() {

        when:
        rxBleDevice.establishConnection(theContext, theAutoConnectValue).subscribe()

        then:
        1 * mockConnector.prepareConnection(theContext, theAutoConnectValue) >> connectionStatePublishSubject

        where:
        theContext    | theAutoConnectValue
        null          | true
        null          | false
        Mock(Context) | true
        Mock(Context) | false
    }

    def "getConnectionState() should emit DISCONNECTED when subscribed and RxBleDevice was not connected yet"() {

        when:
        rxBleDevice.getConnectionState().subscribe(deviceConnectionStateSubscriber)

        then:
        deviceConnectionStateSubscriber.assertValue(DISCONNECTED)
    }

    def "getConnectionState() should emit CONNECTING when subscribed and establishConnection() was subscribed"() {

        given:
        startConnecting()

        when:
        rxBleDevice.getConnectionState().subscribe(deviceConnectionStateSubscriber)

        then:
        deviceConnectionStateSubscriber.assertValue(CONNECTING)
    }

    def "getConnectionState() should emit DISCONNECTED, CONNECTING state on subscribing to establishConnection()"() {

        given:
        rxBleDevice.getConnectionState().subscribe(deviceConnectionStateSubscriber)

        when:
        startConnecting()

        then:
        deviceConnectionStateSubscriber.assertValues(DISCONNECTED, CONNECTING)
    }

    def "getConnectionState() should emit CONNECTED when subscribed after establishConnection() has emitted"() {

        given:
        startConnecting()
        notifyConnectionWasEstablished()

        when:
        rxBleDevice.getConnectionState().subscribe(deviceConnectionStateSubscriber)

        then:
        deviceConnectionStateSubscriber.assertValue(CONNECTED)
    }

    def "getConnectionState() should emit CONNECTING and CONNECTED state when subscribed after subscribing establishConnection() and before it emits RxBleConnection"() {

        given:
        rxBleDevice.getConnectionState().subscribe(deviceConnectionStateSubscriber)

        when:
        startConnecting()
        notifyConnectionWasEstablished()

        then:
        deviceConnectionStateSubscriber.assertValues(DISCONNECTED, CONNECTING, CONNECTED)
    }

    def "getConnectionState() should emit DISCONNECTED state on unsubscribing from establishConnection()"() {

        given:
        def connectionTestSubscriber = new TestSubscriber()
        rxStartConnecting().subscribe(connectionTestSubscriber)
        notifyConnectionWasEstablished()
        connectionTestSubscriber.unsubscribe()

        when:
        rxBleDevice.getConnectionState().subscribe(deviceConnectionStateSubscriber)

        then:
        deviceConnectionStateSubscriber.assertValue(DISCONNECTED)
    }

    def "getConnectionState() should emit DISCONNECTED state when connection was broken"() {

        given:
        rxBleDevice.getConnectionState().subscribe(deviceConnectionStateSubscriber)
        rxStartConnecting().subscribe({ RxBleConnection ignored -> }, { Throwable ignored -> })
        notifyConnectionWasEstablished()

        when:
        dropConnection()

        then:
        deviceConnectionStateSubscriber.assertValues(DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTED)
    }

    def "getConnectionState() should not propagate RxBleConnection.getConnectionState() errors"() {
        given:
        rxBleDevice.getConnectionState().subscribe(deviceConnectionStateSubscriber)
        startConnecting()
        notifyConnectionWasEstablished()

        when:
        emitConnectionStateErrorThroughConnectionStatus()

        then:
        deviceConnectionStateSubscriber.assertNoErrors()
    }

    def "getConnectionState() should not unsubscribe if connection was dropped"() {
        given:
        rxBleDevice.getConnectionState().subscribe(deviceConnectionStateSubscriber)
        startConnecting()
        notifyConnectionWasEstablished()

        when:
        emitConnectionStateErrorThroughConnectionStatus()

        then:
        deviceConnectionStateSubscriber.assertNoTerminalEvent()
    }

    def "should emit connection and stay subscribed after it was established"() {

        given:
        def testSubscriber = new TestSubscriber()

        when:
        rxStartConnecting().subscribe(testSubscriber)
        notifyConnectionWasEstablished()

        then:
        testSubscriber.assertSubscribed()
        testSubscriber.assertValueCount 1
    }

    def "should reuse connection if subscribed with second subscriber"() {

        given:
        def firstSubscriber = new TestSubscriber()
        def secondSubscriber = new TestSubscriber()
        rxStartConnecting().subscribe(firstSubscriber)
        notifyConnectionWasEstablished()

        when:
        rxStartConnecting().subscribe(secondSubscriber)

        then:
        firstSubscriber.assertValueCount 1
        firstSubscriber.assertReceivedOnNext(secondSubscriber.onNextEvents)
    }

    def "should create new connection if previous connection was established and released before second subscriber has subscribed"() {

        given:
        def firstSubscriber = new TestSubscriber()
        def secondSubscriber = new TestSubscriber()
        def subscription = rxStartConnecting().subscribe(firstSubscriber)
        notifyConnectionWasEstablished()
        subscription.unsubscribe()

        when:
        rxBleDevice.establishConnection(Mock(Context), false).subscribe(secondSubscriber)

        then:
        firstSubscriber.assertValueCount 1
        firstSubscriber.assertReceivedOnNextNot(secondSubscriber.onNextEvents)
    }

    def "should unsubscribe from connection if it was dropped"() {

        given:
        def connectionTestSubscriber = new TestSubscriber()
        rxStartConnecting().subscribe(connectionTestSubscriber)
        notifyConnectionWasEstablished()

        when:
        dropConnection()

        then:
        connectionTestSubscriber.isUnsubscribed()
    }

    public void startConnecting() {
        rxStartConnecting().subscribe()
    }

    public Observable<RxBleConnection> rxStartConnecting() {
        return rxBleDevice.establishConnection(Mock(Context), false)
    }

    public void notifyConnectionWasEstablished() {
        mockConnectorEstablishConnectionPublishSubject.onNext(mockConnection)
    }

    public void dropConnection() {
        mockConnectorEstablishConnectionPublishSubject.onError(new BleGattException(BleGattOperationType.CONNECTION_STATE))
    }

    public void emitConnectionStateErrorThroughConnectionStatus() {
        connectionStatePublishSubject.onError(new Throwable("test"))
    }
}
