import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { bancaService } from '../../services/bancaService';
import { useAuthStore } from '../../store/useAuthStore';
import { MovimientoDTO, CuentaDTO } from '../../types';
import { formatCurrency } from '../../utils/format';
import { ArrowLeft, ArrowDownLeft, ArrowUpRight, Calendar, Download, Filter, Search } from 'lucide-react';
import { Boton } from '../../components/ui/Boton';

interface MovimientoAgrupado {
    semana: string;
    movimientos: MovimientoDTO[];
}

const groupByWeek = (movimientos: MovimientoDTO[]): MovimientoAgrupado[] => {
    const grupos: { [key: string]: MovimientoDTO[] } = {};

    const formatDay = (date: Date) => {
        const options: Intl.DateTimeFormatOptions = { weekday: 'short', month: 'short', day: 'numeric' };
        return date.toLocaleDateString('es-ES', options).replace(/\./g, '');
    };

    movimientos.forEach(mov => {
        const date = new Date(mov.fecha);

        date.setHours(0, 0, 0, 0);

        const dayOfWeek = (date.getDay() + 6) % 7;
        const startOfWeek = new Date(date);
        startOfWeek.setDate(date.getDate() - dayOfWeek);

        const weekKey = startOfWeek.toISOString().split('T')[0];

        const endOfWeek = new Date(startOfWeek);
        endOfWeek.setDate(startOfWeek.getDate() + 6);

        const groupTitle = `${formatDay(startOfWeek)} - ${formatDay(endOfWeek)}`;

        if (!grupos[weekKey]) {
            grupos[weekKey] = [];
        }

        if (grupos[weekKey].length === 0) {
            (grupos[weekKey] as any).groupTitle = groupTitle;
        }

        grupos[weekKey].push(mov);
    });

    return Object.keys(grupos)
        .sort((a, b) => b.localeCompare(a))
        .map(key => ({
            semana: (grupos[key] as any).groupTitle,
            movimientos: grupos[key],
        }));
};


const PaginaDetalleCuenta = () => {
    const { numeroCuenta } = useParams();
    const navigate = useNavigate();

    const [movimientos, setMovimientos] = useState<MovimientoDTO[]>([]);
    const [movimientosFiltrados, setMovimientosFiltrados] = useState<MovimientoDTO[]>([]);
    const [gruposSemanales, setGruposSemanales] = useState<MovimientoAgrupado[]>([]);
    const [cuentaInfo, setCuentaInfo] = useState<CuentaDTO | null>(null);
    const [loading, setLoading] = useState(true);

    const [filtroTipo, setFiltroTipo] = useState<'TODOS' | 'INGRESOS' | 'EGRESOS'>('TODOS');
    const [filtroFecha, setFiltroFecha] = useState<'SIEMPRE' | 'SEMANA' | 'MES'>('SIEMPRE');

    const [showDevolucionModal, setShowDevolucionModal] = useState(false);
    const [movimientoADevolver, setMovimientoADevolver] = useState<MovimientoDTO | null>(null);
    const [motivoDevolucion, setMotivoDevolucion] = useState('');
    const [procesandoDevolucion, setProcesandoDevolucion] = useState(false);

    useEffect(() => {
        const cargarDatos = async () => {
            if (!numeroCuenta) return;
            try {
                const movs = await bancaService.getMovimientos(numeroCuenta);
                setMovimientos(movs);

                setMovimientosFiltrados(movs);
                setGruposSemanales(groupByWeek(movs));

                const cuentas = await bancaService.getMisCuentas();
                const actual = cuentas.find(c => c.numeroCuenta === numeroCuenta);
                if (actual) setCuentaInfo(actual);

            } catch (error) {
                console.error(error);
            } finally {
                setLoading(false);
            }
        };
        cargarDatos();
    }, [numeroCuenta]);


    useEffect(() => {
        let resultado = [...movimientos];


        if (filtroTipo === 'INGRESOS') resultado = resultado.filter(m => m.tipo === 'C');
        if (filtroTipo === 'EGRESOS') resultado = resultado.filter(m => m.tipo === 'D');


        const ahora = new Date();
        if (filtroFecha === 'SEMANA') {
            const haceUnaSemana = new Date(ahora.getTime() - 7 * 24 * 60 * 60 * 1000);
            resultado = resultado.filter(m => new Date(m.fecha) >= haceUnaSemana);
        }
        if (filtroFecha === 'MES') {
            const haceUnMes = new Date(ahora.getTime() - 30 * 24 * 60 * 60 * 1000);
            resultado = resultado.filter(m => new Date(m.fecha) >= haceUnMes);
        }


        setMovimientosFiltrados(resultado);
        setGruposSemanales(groupByWeek(resultado));

    }, [filtroTipo, filtroFecha, movimientos]);



    const MovimientoItem = ({ mov, onDevolver }: { mov: MovimientoDTO, onDevolver: (m: MovimientoDTO) => void }) => {
        const esCredito = mov.tipo === 'C';
        const colorMonto = esCredito ? 'text-green-600' : 'text-red-600';
        const SignoIcon = esCredito ? ArrowDownLeft : ArrowUpRight;

        const fecha = new Date(mov.fecha);
        const fechaFormat = fecha.toLocaleDateString('es-ES', { day: 'numeric', month: 'numeric' });
        const horaFormat = fecha.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

        return (
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center p-4 border-b border-gray-100 last:border-b-0 hover:bg-gray-50 transition-colors gap-4">
                <div className="flex items-start gap-3 flex-1">
                    <div className={`p-2 rounded-full mt-1 ${esCredito ? 'bg-green-100 text-green-600' : 'bg-red-100 text-red-600'}`}>
                        <SignoIcon size={18} />
                    </div>
                    <div className="min-w-0">
                        <p className="font-medium text-gray-800 break-words">{mov.descripcion || (esCredito ? 'Depósito' : 'Retiro/Transferencia')}</p>
                        <div className="text-xs text-gray-500 flex flex-wrap gap-x-3 gap-y-1 mt-1">
                            <span>{fechaFormat} {horaFormat}</span>
                            {mov.transaccionId && <span className="font-mono bg-gray-100 px-1 rounded text-gray-600">ID: {mov.transaccionId}</span>}
                            {mov.referencia && <span className="font-mono bg-blue-50 px-1 rounded text-blue-600">Ref: {mov.referencia}</span>}
                        </div>
                    </div>
                </div>

                <div className="flex items-center gap-4 w-full sm:w-auto justify-between sm:justify-end">
                    {esCredito && (
                        <button
                            onClick={() => onDevolver(mov)}
                            className="px-3 py-1 bg-gray-100 hover:bg-red-50 text-gray-600 hover:text-red-600 text-xs font-bold rounded-lg border border-gray-200 transition-colors"
                        >
                            Devolver
                        </button>
                    )}
                    <div className="text-right">
                        <p className={`font-bold ${colorMonto}`}>
                            {esCredito ? '+' : '-'}{formatCurrency(mov.monto)}
                        </p>
                        <p className="text-xs text-gray-400">
                            Saldo: {formatCurrency(mov.saldoNuevo)}
                        </p>
                    </div>
                </div>
            </div>
        );
    };


    if (loading) return <div className="p-10 text-center text-ecusol-primario font-bold">Cargando movimientos...</div>;

    const totalMovimientos = movimientosFiltrados.length;

    const handleDevolver = (mov: MovimientoDTO) => {
        setMovimientoADevolver(mov);
        setMotivoDevolucion('');
        setShowDevolucionModal(true);
    };

    const confirmarDevolucion = async () => {
        if (!movimientoADevolver || !motivoDevolucion) return;
        setProcesandoDevolucion(true);
        try {
            // Usar transaccionId o Referencia segun lo que tenga, idealmente InstructionID original
            const id = movimientoADevolver.transaccionId || movimientoADevolver.referencia;
            await bancaService.solicitarDevolucion(id, motivoDevolucion);
            import('react-hot-toast').then(t => t.toast.success("Devolución solicitada correctamente"));
            setShowDevolucionModal(false);
        } catch (error: any) {
            console.error(error);
            import('react-hot-toast').then(t => t.toast.error(error.message || "Error al solicitar devolución"));
        } finally {
            setProcesandoDevolucion(false);
        }
    };

    return (
        <div className="max-w-5xl mx-auto space-y-6 pb-12">

            <div className="flex items-center gap-4">
                <button onClick={() => navigate('/app/cuentas')} className="p-2 hover:bg-gray-200 rounded-full transition-colors">
                    <ArrowLeft className="text-ecusol-primario" />
                </button>
                <div>
                    <h1 className="text-2xl font-bold text-ecusol-primario">Detalle de Movimientos</h1>
                    <p className="text-gray-500 text-sm">Cuenta Nro: <span className="font-mono font-bold text-gray-700">{numeroCuenta}</span></p>
                </div>
            </div>


            {cuentaInfo && (
                <div className={`p-8 rounded-2xl shadow-lg flex justify-between items-center text-white relative overflow-hidden
            ${cuentaInfo.tipoCuentaId === 1
                        ? 'bg-gradient-to-r from-ecusol-primario to-blue-900' // Azul para Ahorros
                        : 'bg-gradient-to-r from-gray-800 to-black'} // Negro para Corriente
        `}>
                    <div className="relative z-10">
                        <p className="text-white/80 text-sm font-medium mb-1 uppercase tracking-wider">
                            {cuentaInfo.tipoCuentaId === 1 ? 'Cuenta de Ahorros' : 'Cuenta Corriente'}
                        </p>
                        <p className="text-4xl font-bold tracking-tight">{formatCurrency(cuentaInfo.saldo)}</p>
                        <p className="text-xs text-white/60 mt-2">Saldo Disponible</p>
                    </div>
                    <div className="text-right hidden sm:block relative z-10">
                        <span className="bg-white/20 px-3 py-1 rounded-full text-sm font-bold backdrop-blur-sm border border-white/30">
                            {cuentaInfo.estado}
                        </span>
                    </div>

                    <div className="absolute right-0 bottom-0 opacity-10 pointer-events-none">
                        <svg width="200" height="200" viewBox="0 0 200 200" fill="white"><circle cx="150" cy="150" r="100" /></svg>
                    </div>
                </div>
            )}


            <div className="flex flex-col md:flex-row justify-between items-center gap-4 bg-white p-4 rounded-xl shadow-sm border border-gray-100">
                <div className="flex items-center gap-2 overflow-x-auto w-full md:w-auto pb-2 md:pb-0">
                    <Filter size={18} className="text-gray-400 mr-2" />

                    <select
                        className="bg-gray-50 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-ecusol-primario outline-none"
                        value={filtroTipo}
                        onChange={(e) => setFiltroTipo(e.target.value as any)}
                    >
                        <option value="TODOS">Todos los tipos</option>
                        <option value="INGRESOS">Solo Ingresos</option>
                        <option value="EGRESOS">Solo Egresos</option>
                    </select>

                    <select
                        className="bg-gray-50 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-ecusol-primario outline-none"
                        value={filtroFecha}
                        onChange={(e) => setFiltroFecha(e.target.value as any)}
                    >
                        <option value="SIEMPRE">Todo el historial</option>
                        <option value="SEMANA">Últimos 7 días</option>
                        <option value="MES">Este mes</option>
                    </select>
                </div>

                <Boton variante="secundario" tamano="pequeno" icono={<Download size={16} />}>
                    Exportar Excel
                </Boton>
            </div>


            <h2 className="text-xl font-bold text-gray-700 mt-6">Historial ({totalMovimientos} movimientos)</h2>

            {totalMovimientos === 0 ? (
                <div className="p-16 text-center flex flex-col items-center text-gray-400 bg-white rounded-2xl shadow-sm border border-gray-200">
                    <Search size={48} className="mb-4 opacity-20" />
                    <p>No se encontraron movimientos con estos filtros.</p>
                </div>
            ) : (
                <div className="space-y-6">
                    {gruposSemanales.map((grupo, index) => (
                        <div key={index} className="bg-white rounded-2xl shadow-md border border-gray-100 overflow-hidden">

                            <div className="bg-gray-50 p-3 px-6 border-b border-gray-100 flex justify-between items-center">
                                <span className="text-xs font-bold uppercase text-gray-500 flex items-center gap-2">
                                    <Calendar size={14} className='text-ecusol-secundario' /> {grupo.semana}
                                </span>
                                <span className='text-xs text-gray-400'>{grupo.movimientos.length} transacciones</span>
                            </div>


                            <div>
                                {grupo.movimientos.map((mov, movIndex) => (
                                    <MovimientoItem key={movIndex} mov={mov} onDevolver={handleDevolver} />
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {showDevolucionModal && (
                <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-fade-in">
                    <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md p-6 animate-scale-in">
                        <h3 className="text-xl font-bold text-gray-800 mb-2">Devolver Transacción</h3>
                        <p className="text-sm text-gray-500 mb-4">¿Estás seguro de devolver esta transferencia al origen?</p>

                        <div className="bg-gray-50 p-3 rounded-lg border border-gray-200 mb-4">
                            <p className="text-xs font-bold text-gray-500 uppercase">Monto a devolver</p>
                            <p className="text-xl font-bold text-ecusol-primario">{formatCurrency(movimientoADevolver?.monto || 0)}</p>
                            <p className="text-xs text-gray-400 mt-1">{movimientoADevolver?.descripcion}</p>
                        </div>

                        <div className="space-y-2 mb-6">
                            <label className="text-sm font-bold text-gray-700">Motivo de la devolución</label>
                            <textarea
                                className="w-full p-3 border border-gray-300 rounded-xl focus:ring-2 focus:ring-ecusol-primario outline-none"
                                rows={3}
                                placeholder="Escribe la razón (ej: Transferencia errónea)"
                                value={motivoDevolucion}
                                onChange={e => setMotivoDevolucion(e.target.value)}
                            ></textarea>
                        </div>

                        <div className="flex gap-3">
                            <button onClick={() => setShowDevolucionModal(false)} className="flex-1 py-3 rounded-xl font-bold text-gray-500 hover:bg-gray-100">Cancelar</button>
                            <button
                                onClick={confirmarDevolucion}
                                disabled={procesandoDevolucion || !motivoDevolucion}
                                className="flex-1 py-3 rounded-xl bg-red-600 text-white font-bold hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed shadow-lg"
                            >
                                {procesandoDevolucion ? 'Procesando...' : 'Confirmar Devolución'}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default PaginaDetalleCuenta;