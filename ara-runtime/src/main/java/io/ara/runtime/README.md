## 1. Analisi di `AraRuntime` (Il Facade / Orchestrator)

Questa classe implementa i pattern **Facade** e **Builder**. È il punto di ingresso unico per gli utenti del framework.

> **Stato correzioni (2026-07-09).** I rilievi #1, #2 (solo `extraStrategies`), #3, #6 e #7
> sono stati **risolti** e coperti da test in `AraRuntimeLifecycleTest`. I dettagli sono
> annotati inline sotto ciascun punto. #4 e #5 (auto-start) restano scelte di design
> consapevoli; #8 (`AraRuntimeConfig`) resta un'osservazione aperta. Nota inoltre che la
> parte di #2 relativa a `interceptors` **non era un bug**: `interceptors(List<...>)` è un
> setter di lista intera, per cui la sostituzione è la semantica idiomatica attesa.

### Critiche e Aree di Miglioramento (Bugs potenziali)

1. **Race Condition su `start()`:** Il campo `started` è `volatile`, ma i metodi `start()` e `stop()` non sono sincronizzati. Se due thread chiamano `start()` contemporaneamente, potrebbero entrambi superare il check `if (started) return;` e creare due `ExecutorService` diversi, causando un leak del primo. *Soluzione:* Usare un `ReentrantLock` o un blocco `synchronized(this)` attorno ai metodi di lifecycle, oppure usare un `AtomicBoolean` con un confronto e scambio (CAS).
   > **✅ Risolto.** `start()` e `stop()` ora sincronizzano su un `lifecycleLock` dedicato; il check-and-set di `started` avviene dentro la sezione critica.
2. **Bug nel Builder (`extraStrategies` e `interceptors`):** Nel metodo `extraStrategies(ExecutionStrategy... strategies)`, la lista viene sovrascritta (`this.extraStrategies = List.of(strategies)`). Se l'utente chiama il metodo due volte, la seconda chiamata cancella la prima. Dovrebbe essere un accumulo (es. usando una `List` mutabile interna che poi viene resa immutabile nel `build()`). Lo stesso problema si applica ad `interceptors`.
   > **✅ Risolto (parziale).** `extraStrategies(...)` ora accumula (`Collections.addAll` su una lista interna), coerentemente col javadoc che dichiara "call multiple times". La parte su `interceptors` era invece un falso positivo: è un setter di lista, la sostituzione è corretta.
3. **Gestione dell'Executor nel `stop()`:** L'executor viene fermato con `es.shutdown()`, ma non c'è un `awaitTermination`. Se ci sono task in flight, il log dirà "stopped" ma i thread virtuali potrebbero ancora essere in esecuzione in background.
   > **✅ Risolto.** `stop()` ora attende fino a 30s (`awaitTermination`) e poi forza `shutdownNow()`, con log di warning se il drain non completa.
4. **Violazione della State Machine del Lifecycle (submit)**

Il ciclo di vita di un runtime solitamente è uno state machine lineare: CREATED -> STARTED -> STOPPED. Il metodo submit() fa questo:

java

public io.ara.core.agent.AgentFuture submit(AraAgent agent, io.ara.core.agent.AgentTask task) {
if (!started) start();
return io.ara.core.agent.AraAgents.executeAsync(agent, task, agentExecutor);
}
Il problema: Se un utente chiama stop(), lo stato passa a STOPPED e started = false. Se per sbaglio (o per un task in ritardo) viene chiamato submit(), il check if (!started) valuterà true e riavvierà l'intero runtime in modo silenzioso. Verrà creato un nuovo ExecutorService (sovrascrivendo il riferimento a quello vecchio che magari stava ancora facendo shutdown), verranno ricanalizzati gli agenti del provider, ecc.

Soluzione: submit() non dovrebbe avere l'effetto collaterale di avviare il runtime. Dovrebbe lanciare un'eccezione di stato illegale (IllegalStateException("Runtime is not running. Call start() first.")). L'auto-start ha senso solo in createAgent per comodità dell'utente, ma non nelle operazioni asincrone.
5. **Effetti Collaterali Nascosti (Principio di Minima Sorpresa)**

Come appena accennato, createAgent contiene if (!started) start();. Sebbene questo sia comodo (come mostrato nel Quick Start del Javadoc), è un anti-pattern in sistemi complessi.

Se un utente sta costruendo un runtime, crea un agente per testare la configurazione, e poi vuole personalizzare l'executor prima di avviare davvero il sistema, non può. Il semplice atto di creare un agente ha innescato tutto il lifecycle.
Soluzione: Separare la fase di "definizione" da quella di "esecuzione". Lasciare che createAgent lanci un'eccezione se chiamato prima di start(), oppure creare un metodo esplicito createAndStartAgent()
6. **Mancanza di Resilienza nello Shutdown (stop())**

Il metodo stop() itera sugli agenti per distruggerli:

java

registry.all().forEach(agent -> {
factory.destroyPermanently(agent);
instanceContextStore.clear(agent.agentId());
});
Il problema: Cosa succede se factory.destroyPermanently(agent) lancia un'eccezione non controllata (es. un tool dell'agente non riesce a chiudere una connessione al database)?

Il forEach si interromperà bruscamente.
Gli agenti successivi nella lista non verranno distrutti.
Le righe finali (es.shutdown(), started = false) non verranno mai eseguite. Il runtime rimarrà in uno stato zombie (crede di essere started, ma lo scheduler è stoppato e l'executor è chiuso).
Soluzione: Wrappare la distruzione in un blocco try-catch, loggare l'errore per quell'agente specifico, e continuare con il prossimo. Assicurarsi che l'executor venga spento in un blocco finally

> **✅ Risolto.** Ogni `destroyPermanently` è ora avvolto in try-catch (errore loggato, ciclo prosegue); `scheduler.stop()` è protetto allo stesso modo; `shutdownExecutor()` e `started = false` sono in un blocco `finally`, eliminando lo stato zombie.

7. **Accoppiamento Temporale nel Builder (Validazione del Default LLM)**

Nel Builder, l'utente può fare:

java

Builder builder = AraRuntime.builder();
builder.defaultLlmClient("gpt-4-turbo"); // Imposta il default
builder.llmClient("claude-3", claudeClient); // Registra un client, ma NON gpt-4
builder.build(); // BOOM
Il problema: Nel metodo build() non c'è nessuna validazione che verifichi che defaultClientId sia effettivamente presente nella mappa namedClients. Questo causerà un NullPointerException o un comportamento imprevisto profondamente all'interno di DefaultLlmRouter o AgentFactory quando un agente tenterà di fare la prima chiamata LLM senza aver specificato esplicitamente un override.

Soluzione: Aggiungere in cima al build():
java

if (!namedClients.containsKey(defaultClientId)) {
throw new IllegalStateException("Default LLM client '" + defaultClientId + "' not found among registered clients.");
}

> **✅ Risolto.** `build()` ora valida che `defaultClientId` sia presente tra i `namedClients` e lancia `IllegalStateException` (con l'elenco degli id registrati) altrimenti.

8. **Il paradosso di AraRuntimeConfig**

La classe accetta un AraRuntimeConfig nel Builder (runtimeConfig), e lo salva nell'istanza. Tuttavia, durante il build(), nessuna delle dipendenze create (MessageBus, Scheduler, Planner, Factory) usa direttamente cfg per i propri parametri costruttivi. Tutto viene pilotato dai singoli campi del Builder.

Questo fa sorgere il dubbio: a cosa serve realmente AraRuntimeConfig in questo momento? Se serve solo per il logging (config.name()), allora è sovraccarico. Se in futuro dovesse contenere flag che modificano il comportamento del builder (es. cfg.maxParallelTools()), il codice attuale non li leggerebbe. C'è un rischio che AraRuntimeConfig diventi un "Configuration Ghost" (un oggetto che sembra configurare il sistema ma che in realtà viene ignorato a favore dei campi del Builder).
