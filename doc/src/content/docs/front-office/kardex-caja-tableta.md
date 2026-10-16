---
title: Kárdex, caja y tableta
description: El kárdex completo del huésped y su viaje al MDM y a Salesforce, la caja de la estancia (cobros, anticipos, recibos, límite y crédito cancelado) y el check-in en la tableta del lobby, simulado.
---

Tres piezas que acercan el front office al día a día de una recepción con OPERA. Las tres reutilizan los
casos de uso que ya había: lo que se hace aquí llega al MDM, a la caja o a las operaciones del check-in
igual que si lo hiciera recepción a mano.

## El kárdex completo

En el paso **Identidad** del check-in y en el drawer de cada huésped de la reserva se usa el mismo
formulario de kárdex. Se organiza en cuatro columnas y, al guardar la identidad, vuelve a evaluar el
reconocimiento del cliente. Recoge los siguientes datos:

| Bloque | Campos |
| :-- | :-- |
| Identidad | Nombre y **apellidos** por separado, documento, tipo, país y **fecha de expedición**, caducidad, nacionalidad, fecha y lugar de nacimiento, sexo |
| Contacto | Email, teléfono, **fax** |
| Dirección | Dirección, código postal, población, **provincia**, país de residencia |
| Kárdex | **Idioma**, **nº Riu Class**, **acepta publicidad** |
| Solo lectura | **Titular o acompañante**, y si el kárdex es **provisional** (solo lo que trajeron la reserva y el escaneo) o **completado en recepción** |

Lo que exige la normativa del destino sigue en los datos de registro (`pax_registration_data`); lo demás,
en `pax_kardex`. Al guardar, además del cambio de contacto de siempre (`ProposeChange`, que decide
Salesforce), sale **`RecordKardex`** al MDM con todo lo que el huésped ha declarado.

**En el MDM** (`Kardexes`): el kárdex es la palabra del huésped en el mostrador, así que **lo último manda**
en sexo, idioma, dirección, lugar de nacimiento, fax, Riu Class y publicidad. La fecha de nacimiento y la
nacionalidad solo rellenan huecos (un documento escaneado es mejor prueba), y el documento gana su fecha
de expedición y su caducidad. El número Riu Class queda además como referencia cruzada `RIU_CLASS`. Viaja
en el `GoldenRecord` (`profile`) a los hoteles y a Salesforce: dirección postal estándar
(`MailingStreet`, `MailingCity`, `MailingPostalCode`, `MailingCountryCode`), `Fax`, `HasOptedOutOfEmail`
(lo contrario de «acepta publicidad») y los campos propios `Sexo__c`, `Idioma__c`,
`Lugar_Nacimiento__c`, `Provincia__c` y `Riu_Class__c`, en la sección *Kárdex (MDM)*. Solo se envía lo
que el MDM tiene: un cliente sin kárdex no vacía lo que un steward escribió en Salesforce.

:::note
La org tiene activados los picklists de país y provincia: el país va como código ISO-2
(`MailingCountryCode`, un `ESP` se convierte en `ES`) y la provincia en un campo propio, porque el
picklist de provincias rechazaría el texto libre.
:::

Opera no recibe el kárdex de recepción (tampoco antes): sus perfiles salen de la reserva del CRS.

## La caja de la estancia

Icono **Caja** en la cabecera de la reserva (por llegar y en casa): la cuenta de la estancia en la moneda
del hotel (`frontoffice.currency`, EUR por defecto).

La pantalla se divide en **Cobrar**, **Crédito**, **Cobros y recibos** y **Devolver o anular**. El email
se pide para un link de pago y la referencia para una transferencia; las acciones de fijar límite,
cancelar y restablecer el crédito están en la pestaña Crédito.

- **Saldo pendiente** = cargos del folio − lo cobrado. Con el desglose: cargos, cobrado y anticipos.
- **Cobrar**, como *cobro* o como **anticipo**, con estas formas de pago:
  - **Efectivo**, **transferencia** o **manual**: queda cobrado al momento.
  - **Datáfono**: simulado. Aprueba con un número de autorización, salvo un importe acabado en **,99**,
    que es la tarjeta denegada de la demo.
  - **Link de pago por email**: queda *pendiente* hasta que el huésped paga en `/pagar/<token>`, una
    página sin login (en la demo, el botón «Pagar»; no hay pasarela).
- Cada cobro tiene su **recibo** en PDF (`/caja/recibo/…`), con numeración propia. Se puede **devolver**
  un cobro o **anular** un link.
- **Imprimir proforma del folio** en cualquier momento de la estancia: cargos, cobros y saldo
  (`/caja/proforma/…`). Los enlaces van firmados y caducan, como el de la factura.
- **Límite de crédito**: por defecto, la preautorización del check-in. Se puede fijar otro, y la caja
  avisa cuando se supera.
- **Crédito cancelado**: deja el límite a cero. Desde ese momento **no se carga nada a la habitación**
  («Añadir cargo» lo rechaza), hasta que se **restablece**.

En el **check-out** se cobra solo el **saldo pendiente**, ya descontados los anticipos y cobros de la
estancia, y queda como un cobro más con su recibo. Si el datáfono deniega, el check-out no sigue.

**Cada cobro va al folio de Opera**, el maestro del folio, en cuanto se cobra: así el saldo de Opera es lo
que queda por cobrar y su check-out liquida solo eso. Sale `payment-taken` en `front-office-events` y
arranca **`registrar-cobro`** (una devolución, `payment-refunded` → **`devolver-cobro`**); ver
[Procesos](/guias/procesos/#los-cobros-de-la-caja-al-folio-del-pms-registrar-cobro-devolver-cobro). En la
caja, debajo de cada cobro, su estado en Opera: «pendiente — cobro enviado», «en el folio · nº de
transacción», «devuelto» o «rechazado — por qué».

- Solo lo **cobrado**: un link de pago pendiente o una tarjeta denegada no van; el link, cuando se paga.
- Un cobro de una reserva que Opera aún **no tiene en casa** (un anticipo antes del check-in) **espera**, y
  el check-in en Opera lo suelta.

| Forma de pago (front office) | En Opera (XMAR) |
| :-- | :-- |
| Efectivo, manual | `CASH` (Pago Efectivo) |
| Transferencia | `BT` (Bank Transfer) |
| Tarjeta (datáfono) | `VI` (Visa), `OPERA_PAYMENT_CARD_METHOD` |
| Link de pago | `VI/OL` (Visa Manual), `OPERA_PAYMENT_LINK_METHOD` |

Configurable en `ohip.payments` de `pms-integration-service`. Las formas de pago de XMAR salen de
`GET /lov/v1/listOfValues/hotels/XMAR/paymentMethods`.

## La empresa: a quién se factura

La empresa es a quien se factura la estancia cuando no se factura ni a la agencia ni al huésped. Es un
partner de tipo `Company` del ERP, el maestro de partners, que la integración proyecta a Opera como perfil
*Company* («proyectar-interlocutor»).

- **Dónde se ve.** En la cabecera de la reserva, como **EMPRESA**, en la misma línea que PRECIO CRS, TOTAL
  OPERA, AGENCIA y FIDELIDAD («—» sin empresa). Llega con la reserva de Opera: su perfil *Company*, cuyo
  `CorporateId` es el código del partner. La agencia es el *TravelAgent* (o el *Source*), ya no la empresa.
- **Elegirla, cambiarla o quitarla.** Con **✎** junto a EMPRESA en la cabecera: el drawer abre un
  listado de empresas del ERP (`GET /partners?type=Company`), con búsqueda por texto y columnas de
  código, nombre, NIF y ciudad. Pulsar una fila la asigna a la estancia y cierra el selector. El front
  office sale con `company-changed` en `front-office-events` y arranca **`asignar-empresa`**: el perfil se adjunta a la
  reserva de Opera y se solicita enrutarle el alojamiento y la pensión en la ventana 2 del folio. En
  XMAR aún no está verificado que Opera aplique ese enrutado; ver [Problemas conocidos](/operacion/problemas-conocidos/#salesforce-y-opera).
  Hasta que Opera muestra la empresa, una proyección de la reserva tal como estaba no deshace lo que
  eligió recepción.
- **Corregir sus datos.** Nombre, CIF, dirección, email y teléfono se editan en el mismo drawer y se
  guardan en el ERP (`PUT /partners/{code}`). El ERP los anuncia y `ensure-partner-profile` actualiza el
  perfil de Opera cuando la versión del partner es más nueva que la proyectada.
- **En la factura.** La proforma del front office dice «Facturar a: empresa · CIF · dirección».

## Check-in en la tableta (Civitfun, simulado)

Icono de **tableta** en la cabecera de una reserva que llega. Como el escáner de documentos, **no hay tableta**:
la simulación hace lo que haría el huésped en ella, y un diálogo de progreso cuenta cada paso:

1. **Escanea su documento**, que va al MDM como un escaneo de recepción.
2. **Completa sus datos**: email, dirección de su país, idioma y publicidad. Es su kárdex, que va al MDM.
3. **Elige habitación**: uno de cada tres intenta el **upgrade** a la suite 1401 si puede asignarla;
   los demás eligen otra libre y lista de su tipo. Si no hay una alternativa válida, conservan la suya.
4. **Elige extras**: uno o dos suplementos opcionales del catálogo, sin los ya incluidos; uno de cada
   cuatro no quiere ninguno. La selección queda cerrada como cuando la completa recepción.
5. **Garantiza la estancia**: preautoriza su tarjeta o, uno de cada cuatro, **la paga** como anticipo
   con su recibo.
6. **Firma el registro**.

Las respuestas salen de la reserva: la misma reserva da siempre las mismas. Al acabar, la reserva
muestra lo completado —documentación, habitación, extras, firma y cobro—. El asistente de recepción
ofrece las operaciones que sigan pendientes, incluida la llave y el wifi, antes de confirmar el check-in.
