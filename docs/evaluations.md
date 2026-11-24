# Evaluaciones del asistente

[English version](en/evaluations.md) · [README](../README.es.md)

## Ejecución

```bash
cd ai-service && pip install -e ".[evals]" pytest ruff mypy
pytest -q                                   # loop, guardrails y el arnés de evals, con un LLM guionado
python -m evals.run --tag smoke             # escenarios contra el modelo real (necesita ANTHROPIC_API_KEY)
python -m evals.run --model claude-haiku-5-5 --model claude-sonnet-5-5 --out report.json
```

Los escenarios (`ai-service/evals/scenarios/*.yaml`) corren contra `fake_core.py`, la API interna en memoria, sin levantar Spring: reservas precisas y ambiguas, confirmaciones, grounding, fuera de tema, prompt injection, configuración del dueño y derivación. Primero chequeos determinísticos; un LLM juez solo para el tono. En CI corre el set `smoke` cuando cambia el agente y existe el secret `ANTHROPIC_API_KEY`.

## Resultados documentados

**Resultados** (18/11/2026, prompt `2026-11-18.3`, `python -m evals.run`):

| Modelo | Escenarios OK | Costo promedio / escenario | Latencia promedio |
|---|---|---|---|
| claude-haiku-5-5 | 29/29 (100%) | US$ 0,00054 | 3,9 s |

La prueba de punta a punta con el modelo real encontró un bug que los evals no cubrían: ante un "sí" de más después de una reserva automática, el agente volvía a preparar el mismo turno, recibía "horario ocupado" (lo ocupaba su propia reserva) y le decía al cliente que no había quedado. Ahora `prepare_booking` devuelve `already_booked`, y el escenario `booking_extra_yes_after_booked` lo cubre.


## Alcance de las métricas

El [runner](../ai-service/evals/run.py) suma el coste de todos los turnos de cada escenario y mide el tiempo transcurrido del escenario completo. El coste reportado corresponde al uso del agente, no necesariamente al evaluador. Las métricas corresponden a una ejecución documentada con un core simulado; no son métricas de producción ni una garantía de exactitud general. Las pruebas con modelos reales consumen tokens.
